#!/usr/bin/env python3
"""
Hybrid Cellular-Bluetooth Mobile Application Server
Serves the interactive mobile client and executes real-time SQLite R-Tree queries,
4-way modality availability arbitration, and zero-trust cryptographic functions.
"""

import os
import sys
import json
import math
import time
import hmac
import hashlib
import struct
import sqlite3
import base64
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse

PORT = 8080
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_FILE = os.path.join(BASE_DIR, "ecosystem_live.db")
SCHEMA_FILE = os.path.join(BASE_DIR, "sql", "schema.sql")
WEB_APP_DIR = os.path.join(BASE_DIR, "web_app")

# Shared Simulation State
app_state = {
    "fused_location": {
        "lat": 37.774910,
        "lon": -122.419405,
        "accuracy": 1.8,
        "confidence": 98,
        "primary_source": "BLE 5.1 AoA (Micro-Location)"
    },
    "power_state": "CELL_MAINTENANCE",
    "battery_pct": 86,
    "modem_status": "3GPP eDRX Cycle (40.96s Paging)",
    "sources": {
        "gps": {
            "name": "GPS / GNSS Satellite Fix",
            "enabled": True,
            "available": True,
            "lat": 37.774929,
            "lon": -122.419416,
            "accuracy": 8.5,
            "satellites": 7,
            "hdop": 1.1,
            "reason": "Active 3D fix (7 SVs locked, HDOP 1.1)"
        },
        "ble": {
            "name": "BLE 5.1 AoA Proximity",
            "enabled": True,
            "available": True,
            "lat": 37.774910,
            "lon": -122.419405,
            "accuracy": 1.8,
            "azimuth": 18.4,
            "rssi": -58,
            "reason": "Constant Tone Extension (CTE) detected @ 2.44 GHz"
        },
        "cell": {
            "name": "Cell Tower R-Tree (Offline)",
            "enabled": True,
            "available": True,
            "lat": 37.774950,
            "lon": -122.419380,
            "accuracy": 140.0,
            "cid": 401821,
            "lac": 12050,
            "ta": 3,
            "reason": "Matched 3 towers in local SQLite R-Tree"
        },
        "mesh": {
            "name": "P2P Mesh Peer Anchor",
            "enabled": False,
            "available": False,
            "lat": 37.774900,
            "lon": -122.419420,
            "accuracy": 35.0,
            "peer_count": 0,
            "reason": "No ad-hoc Wi-Fi Direct peer connected"
        }
    },
    "incoming_call": None,
    "sms_log": []
}

def init_database():
    """Initializes SQLite database with schema and sample OpenCelliD towers."""
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()
    if os.path.exists(SCHEMA_FILE):
        with open(SCHEMA_FILE, "r", encoding="utf-8") as f:
            cursor.executescript(f.read())
        conn.commit()

    # Seed sample cell towers if empty
    count = cursor.execute("SELECT count(*) FROM rtree_cell_towers;").fetchone()[0]
    if count == 0:
        sample_towers = [
            (101, "LTE", 310, 410, 12050, 401821, 37.7749, -122.4194, 1200, 350),
            (102, "LTE", 310, 410, 12050, 401822, 37.7785, -122.4150, 950, 280),
            (103, "LTE", 310, 410, 12050, 401823, 37.7710, -122.4240, 1400, 190),
            (104, "NR",  310, 410, 12050, 409950, 37.7760, -122.4170, 400,  80),
        ]
        for idx, radio, mcc, mnc, lac, cid, lat, lon, rng, smp in sample_towers:
            lat_delta = rng / 111000.0
            lon_delta = rng / (111000.0 * math.cos(math.radians(lat)))
            cursor.execute(
                "INSERT OR IGNORE INTO rtree_cell_towers VALUES (?, ?, ?, ?, ?)",
                (idx, lon - lon_delta, lon + lon_delta, lat - lat_delta, lat + lat_delta)
            )
            cursor.execute(
                "INSERT OR IGNORE INTO cell_tower_metadata VALUES (?, ?, ?, ?, ?, ?, ?, ?, -75.0, strftime('%s','now'))",
                (idx, radio, mcc, mnc, lac, cid, rng, smp)
            )
        conn.commit()
    conn.close()

def recalculate_fused_location():
    """Applies LocationFusionArbiter covariance math across enabled & available sources."""
    viable = []
    sources = app_state["sources"]

    # Micro-location BLE AoA precedence
    if sources["ble"]["enabled"] and sources["ble"]["available"]:
        viable.append(("BLE_AOA", sources["ble"]["lat"], sources["ble"]["lon"], sources["ble"]["accuracy"]))
    if sources["gps"]["enabled"] and sources["gps"]["available"]:
        viable.append(("GPS_GNSS", sources["gps"]["lat"], sources["gps"]["lon"], sources["gps"]["accuracy"]))
    if sources["cell"]["enabled"] and sources["cell"]["available"]:
        viable.append(("CELLULAR_RTREE", sources["cell"]["lat"], sources["cell"]["lon"], sources["cell"]["accuracy"]))
    if sources["mesh"]["enabled"] and sources["mesh"]["available"]:
        viable.append(("P2P_MESH_ANCHOR", sources["mesh"]["lat"], sources["mesh"]["lon"], sources["mesh"]["accuracy"]))

    if not viable:
        app_state["fused_location"] = {
            "lat": 0.0,
            "lon": 0.0,
            "accuracy": 9999.0,
            "confidence": 0,
            "primary_source": "NO SOURCE AVAILABLE"
        }
        return

    # Check for BLE micro-precision dominance (< 5.0m uncertainty)
    ble_fix = next((c for c in viable if c[0] == "BLE_AOA" and c[3] <= 5.0), None)
    if ble_fix:
        app_state["fused_location"] = {
            "lat": ble_fix[1],
            "lon": ble_fix[2],
            "accuracy": ble_fix[3],
            "confidence": 98,
            "primary_source": "BLE 5.1 AoA (Micro-Location)"
        }
        return

    # Inverse-Variance Covariance Fusion: w = 1 / (sigma^2)
    total_w = 0.0
    sum_lat = 0.0
    sum_lon = 0.0
    for name, lat, lon, acc in viable:
        w = 1.0 / (acc * acc)
        sum_lat += lat * w
        sum_lon += lon * w
        total_w += w

    fused_lat = sum_lat / total_w
    fused_lon = sum_lon / total_w
    fused_acc = math.sqrt(1.0 / total_w)
    primary = min(viable, key=lambda c: c[3])[0]

    confidence = 92 if fused_acc < 25 else (75 if fused_acc < 150 else 50)
    app_state["fused_location"] = {
        "lat": round(fused_lat, 6),
        "lon": round(fused_lon, 6),
        "accuracy": round(fused_acc, 1),
        "confidence": confidence,
        "primary_source": primary
    }

class MobileAppRequestHandler(SimpleHTTPRequestHandler):

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=WEB_APP_DIR, **kwargs)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/state":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(app_state).encode("utf-8"))
            return
        elif parsed.path == "/api/towers":
            conn = sqlite3.connect(DB_FILE)
            cursor = conn.cursor()
            rows = cursor.execute("""
                SELECT m.cid, m.radio_type, (r.min_lat + r.max_lat)/2.0, (r.min_lon + r.max_lon)/2.0, m.range_meters
                FROM cell_tower_metadata m
                JOIN rtree_cell_towers r ON m.cell_idx = r.cell_idx
            """).fetchall()
            conn.close()
            towers = [
                {"cid": r[0], "radio": r[1], "lat": r[2], "lon": r[3], "range": r[4]}
                for r in rows
            ]
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(towers).encode("utf-8"))
            return

        # Serve static web app assets from web_app directory
        if self.path == "/" or self.path == "/index.html":
            self.path = "/index.html"

        return super().do_GET()

    def do_POST(self):
        parsed = urlparse(self.path)
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        try:
            data = json.loads(body) if body else {}
        except Exception:
            data = {}

        if parsed.path == "/api/toggle":
            source_key = data.get("source")
            if source_key in app_state["sources"]:
                new_val = not app_state["sources"][source_key]["enabled"]
                app_state["sources"][source_key]["enabled"] = new_val
                app_state["sources"][source_key]["available"] = new_val
                recalculate_fused_location()

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(app_state).encode("utf-8"))
            return

        elif parsed.path == "/api/trigger-call":
            caller_name = data.get("name", "Emergency Security Node")
            caller_num = data.get("number", "+1 (555) 019-2834")
            app_state["incoming_call"] = {
                "name": caller_name,
                "number": caller_num,
                "time": time.strftime("%H:%M:%S")
            }
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(app_state).encode("utf-8"))
            return

        elif parsed.path == "/api/dismiss-call":
            app_state["incoming_call"] = None
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(app_state).encode("utf-8"))
            return

        elif parsed.path == "/api/trigger-sms":
            secret = b"pre_shared_device_sms_secret_32"
            cmd_code = 0x10  # POLL_LOCATION
            nonce = os.urandom(8)
            mac = hmac.new(secret, bytes([cmd_code]) + nonce, hashlib.sha256).digest()[:8]
            sms_outbound = "MT#" + base64.urlsafe_b64encode(bytes([cmd_code]) + nonce + mac).decode()

            # Pack response
            fused = app_state["fused_location"]
            bin_data = struct.pack(">ddfBI", fused["lat"], fused["lon"], fused["accuracy"], 86, int(time.time()))
            cipher_bytes = bytes([b ^ secret[i % len(secret)] for i, b in enumerate(bin_data)])
            sms_reply = "MT#" + base64.urlsafe_b64encode(b"\x01\x11" + cipher_bytes).decode()

            log_entry = {
                "time": time.strftime("%H:%M:%S"),
                "outbound_sms": sms_outbound,
                "inbound_reply": sms_reply,
                "decrypted_lat": fused["lat"],
                "decrypted_lon": fused["lon"],
                "accuracy": fused["accuracy"]
            }
            app_state["sms_log"].insert(0, log_entry)

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(log_entry).encode("utf-8"))
            return

        self.send_response(404)
        self.end_headers()

def main():
    init_database()
    recalculate_fused_location()
    server_address = ("127.0.0.1", PORT)
    httpd = ThreadingHTTPServer(server_address, MobileAppRequestHandler)
    httpd.allow_reuse_address = True
    print("\n" + "=" * 65)
    print(" >>> HYBRID TRACKER LIVE MOBILE APP SERVER ONLINE")
    print(f" >>> Open in browser: http://localhost:{PORT}")
    print("=" * 65 + "\n")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping application server...")
        httpd.server_close()

if __name__ == "__main__":
    main()
