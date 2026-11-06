-- ============================================================================
-- Hybrid Tracking & Cross-Device Notification Mirroring Database Schema
-- SQLite 3.38+ with SQLite R*Tree Module enabled (SQLITE_ENABLE_RTREE=1)
-- Optimization: Write-Ahead Logging (WAL) and synchronous = NORMAL
-- ============================================================================

-- Execute at connection initialization:
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA temp_store = MEMORY;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
PRAGMA mmap_size = 268435456; -- 256MB memory-mapped I/O for lightning R-Tree lookups

-- ----------------------------------------------------------------------------
-- 1. Spatial Index: OpenCelliD Regional Cell Towers (R-Tree Virtual Table)
-- ----------------------------------------------------------------------------
-- Stores bounding boxes of cell coverage polygons.
-- Coordinates are stored as IEEE 754 floating point: minX/maxX (Longitude), minY/maxY (Latitude).
CREATE VIRTUAL TABLE IF NOT EXISTS rtree_cell_towers USING rtree(
    cell_idx,          -- Unique 64-bit integer index (links to cell_tower_metadata)
    min_lon, max_lon,  -- X bounds [-180.0, 180.0]
    min_lat, max_lat   -- Y bounds [-90.0, 90.0]
);

-- Auxiliary table storing cell identification parameters and RF properties
CREATE TABLE IF NOT EXISTS cell_tower_metadata (
    cell_idx INTEGER PRIMARY KEY,
    radio_type TEXT NOT NULL,         -- 'GSM', 'UMTS', 'LTE', 'NR'
    mcc INTEGER NOT NULL,             -- Mobile Country Code
    mnc INTEGER NOT NULL,             -- Mobile Network Code
    lac INTEGER NOT NULL,             -- Location Area Code / TAC
    cid INTEGER NOT NULL,             -- Cell ID
    range_meters INTEGER NOT NULL,    -- Estimated transmission radius
    samples INTEGER NOT NULL,         -- Number of data points in OpenCelliD
    avg_rssi REAL,                    -- Baseline signal strength
    created_at INTEGER DEFAULT (strftime('%s', 'now')),
    UNIQUE(mcc, mnc, lac, cid)
);

CREATE INDEX IF NOT EXISTS idx_cell_lookup ON cell_tower_metadata(mcc, mnc, lac, cid);

-- ----------------------------------------------------------------------------
-- 2. Device Ecosystem Registry & Cryptographic Keys
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS registered_devices (
    device_id TEXT PRIMARY KEY,       -- UUIDv4 or hardware enclave fingerprint
    user_id TEXT NOT NULL,
    device_name TEXT NOT NULL,
    device_type TEXT NOT NULL,        -- 'PRIMARY_PHONE', 'TABLET', 'BEACON_TAG', 'DESKTOP'
    fido2_credential_id BLOB,         -- WebAuthn / Passkey credential identifier
    public_identity_key BLOB NOT NULL,-- Hardware Enclave Public Key (ECDSA P-256 / Ed25519)
    shared_sms_secret BLOB NOT NULL,  -- Pre-shared key for SMS HMAC & AES-256-GCM
    last_known_eid BLOB,              -- Most recent 1024s BLE Ephemeral Identifier
    eid_rotation_epoch INTEGER,       -- Timestamp epoch for EID validation
    is_trusted INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL
);

-- ----------------------------------------------------------------------------
-- 3. Ephemeral Telemetry Cache (Offline-First Store-and-Forward)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS location_telemetry (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    source_type TEXT NOT NULL,        -- 'CELL_TRIANGULATION', 'BLE_AOA', 'GPS', 'P2P_PEER'
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    altitude REAL,
    horizontal_accuracy REAL NOT NULL,
    aoa_azimuth REAL,                 -- BLE 5.1 Direction Finding Azimuth (degrees)
    aoa_elevation REAL,               -- BLE 5.1 Direction Finding Elevation (degrees)
    power_state TEXT NOT NULL,        -- 'STATIONARY_SAFE', 'CELL_MAINTENANCE', 'ACTIVE_TRANSIT'
    encrypted_payload BLOB NOT NULL,  -- E2EE ciphertext with Ephemeral Identity Key
    iv BLOB NOT NULL,                 -- 12-byte GCM initialization vector
    tag BLOB NOT NULL,                -- 16-byte authentication tag
    is_synced INTEGER NOT NULL DEFAULT 0,
    recorded_at INTEGER NOT NULL,
    FOREIGN KEY(device_id) REFERENCES registered_devices(device_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_telemetry_sync ON location_telemetry(is_synced, recorded_at);

-- ----------------------------------------------------------------------------
-- 4. Mirrored Notifications & Call State Outbox/Inbox
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_mirror_events (
    event_id TEXT PRIMARY KEY,
    origin_device_id TEXT NOT NULL,
    package_name TEXT NOT NULL,
    title_obfuscated TEXT,
    encrypted_content BLOB NOT NULL,
    notification_type TEXT NOT NULL,  -- 'CALL_INCOMING', 'SMS_ALERT', 'APP_NOTIFICATION', 'PERIMETER_BREACH'
    urgency_tier INTEGER NOT NULL,    -- 1: Push, 2: WebSocket, 3: Silent SMS
    status TEXT NOT NULL,             -- 'QUEUED', 'RELAYED_LOCAL_P2P', 'DISPATCHED_CLOUD', 'ACKNOWLEDGED'
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(origin_device_id) REFERENCES registered_devices(device_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_mirror_queue ON notification_mirror_events(status, urgency_tier);

-- ----------------------------------------------------------------------------
-- 5. P2P Mesh Network Node State & CRDT Peer Vector Clock
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mesh_peer_nodes (
    peer_mac_hash TEXT PRIMARY KEY,
    discovered_via TEXT NOT NULL,     -- 'WIFI_DIRECT', 'BLE_5_ADVERT'
    peer_device_id TEXT,
    is_bridge_capable INTEGER NOT NULL DEFAULT 0,
    link_rssi INTEGER NOT NULL,
    vector_clock TEXT NOT NULL,       -- JSON serialized vector clock for CRDT sync
    last_heartbeat INTEGER NOT NULL
);
