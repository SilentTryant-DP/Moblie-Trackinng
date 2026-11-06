# Hybrid Cellular-Bluetooth Tracking & Notification Mirroring Ecosystem

An enterprise-grade, offline-first mobile architecture uniting **Macro-Location Cell Tower Triangulation**, **Micro-Location BLE 5.1 Angle-of-Arrival (AoA)**, **Ad-Hoc P2P Mesh Networking**, and **Cross-Device Alert Mirroring** under a **Zero-Trust Cryptographic Model**.

---

## Architecture Blueprint Overview

```mermaid
graph TD
    subgraph Client Layer [Hybrid Mobile Client (Jetpack Compose / KMP)]
        UI[MainActivity / TrackingDashboardScreen]
        VM[TrackerViewModel / StateFlow]
        Arbiter[LocationFusionArbiter Engine]
        DB[(SQLite R-Tree + WAL)]
        Crypto[EIK & SECP160R1 Crypto Engine]
    end

    subgraph Macro Location [Cellular Triangulation Engine]
        TM[TelephonyCallback / Modem]
        OpenCelliD[(Regional OpenCelliD R-Tree)]
        Centroid[Path-Loss Weighted Centroid]
    end

    subgraph Micro Location [Bluetooth Proximity & Direction]
        BLE_AoA[BLE 5.1 Direction Finding AoA]
        EID[1024s Rotating EID Generator]
    end

    subgraph P2P Mesh [Offline Ad-Hoc Mesh Layer]
        WiFiDirect[Wi-Fi Direct Group Owner/Client]
        BLEMesh[BLE Advertising Mesh]
        BridgeMode[Dynamic Cellular Gateway Bridge]
    end

    subgraph Tiered Mirroring [Cross-Device Alert Mirroring]
        T1[Tier 1: FCM / APNs Push]
        T2[Tier 2: WebSocket Streaming <50ms]
        T3[Tier 3: Silent SMS Type 0 Fallback]
        VoIP[PushKit + CallKit DND Bypass]
    end

    TM --> Centroid
    OpenCelliD --> Centroid
    Centroid --> Arbiter
    BLE_AoA --> Arbiter
    Arbiter --> DB
    Arbiter --> VM
    VM --> UI
    DB --> Crypto
    Crypto --> WiFiDirect
    Crypto --> BLEMesh
    WiFiDirect --> BridgeMode
    BridgeMode --> T2
    BridgeMode --> T1
    T3 --> TM
    VoIP --> UI
```

---

## Full-Fledged Application Structure

```
├── app_server.py                   # Live interactive backend server (REST API & SQLite R-Tree queries)
├── simulate_ecosystem.py           # Algorithmic verification suite across all 4 tracking modalities
├── sql/
│   └── schema.sql                  # SQLite R*Tree virtual table, WAL & NORMAL sync pragmas
├── web_app/
│   └── index.html                  # Interactive Mobile App UI (Leaflet Map, Radar sweep, Call modal)
├── core/
│   ├── crypto/
│   │   ├── EikCryptoEngine.kt      # SECP160R1 projection & 1024s rotating EID via AES-ECB-256
│   │   └── SmsPayloadCrypto.kt     # Compact authenticated binary SMS packaging & AES-GCM
│   ├── engine/
│   │   ├── LocationFusionArbiter.kt# 4-source availability probe & covariance fusion engine
│   │   ├── CellularTriangulation.kt# Path-loss & LTE Timing Advance weighted centroid triangulation
│   │   ├── BleDirectionFinding.kt  # BLE 5.1 CTE phase difference & AoA azimuth resolution
│   │   └── PowerStateMachine.kt    # Stationary safe-zone detection & eDRX/PSM modem power control
│   └── mesh/
│       └── MeshNetworkProtocol.kt  # CRDT vector clocks & dynamic cellular bridge election
├── android/
│   ├── app/
│   │   ├── build.gradle.kts        # Jetpack Compose, Material 3, Android 15 compileSdk 35
│   │   └── src/main/
│   │       ├── AndroidManifest.xml # Permissions for fine location, telephony, SMS, FGS dataSync
│   │       ├── res/values/         # strings.xml, colors.xml
│   │       └── java/com/mobiletracker/
│   │           ├── MainActivity.kt # Compose navigation & scaffold
│   │           ├── viewmodel/TrackerViewModel.kt
│   │           └── ui/
│   │               ├── TrackingDashboardScreen.kt # Fused location hero card & 4-way switcher
│   │               ├── BleAoARadarScreen.kt       # Canvas-rendered direction finding radar
│   │               ├── NotificationMirrorScreen.kt# Mirrored VoIP calls & SMS console
│   │               └── theme/Theme.kt             # Dark Material You theme
│   ├── services/
│   │   ├── HybridTrackingService.kt# Android 15 dataSync 6-hour limit & Service.onTimeout() ANR fix
│   │   └── NotificationMirrorListener.kt # Intercepts and E2EE-encrypts calls & alerts
│   ├── telephony/
│   │   ├── AndroidCellularMonitor.kt# TelephonyCallback.CellInfoListener & R-Tree lookups
│   │   └── EncryptedSmsReceiver.kt # BroadcastReceiver silent SMS interceptor & coordinate sender
│   ├── location/
│   │   └── AndroidLocationArbiter.kt# Native hardware prober bridging 4 location sources
│   └── p2p/
│       └── WifiDirectBridgeManager.kt # Wi-Fi Direct mesh socket server & dynamic backhaul relay
└── ios/
    ├── location/
    │   └── IOSLocationManager.swift# CoreLocation sandbox compliance & BGAppRefreshTask
    └── voip/
        └── CallKitVoIPManager.swift# PushKit VoIP & CallKit native full-screen incoming call UI
```

---

## How to Run the App Right Now

### 1. Launch the Live Mobile Application on your PC:
Double-click `run.bat` or run:
```powershell
python app_server.py
```
Open **[http://localhost:8080](http://localhost:8080)** in your browser.
* **Interactive Map**: View cell towers from SQLite R-Tree, GPS pins, and fused location circles.
* **4-Way Modality Switcher**: Toggle GPS, BLE, Cell R-Tree, or P2P Mesh to see the fusion arbiter recalculate live!
* **AoA Radar**: Visual direction-finding sweep with azimuth pointer.
* **Call & SMS Mirroring**: Simulate mirrored calls and dispatch silent SMS emergency polls.

### 2. Run the Verification Suite:
Double-click `run_simulation.bat` or run:
```powershell
python simulate_ecosystem.py
```

### 3. Open in Android Studio:
Open the repository in **Android Studio (API 35)** and deploy `android/app` to a physical Android device or emulator. All Kotlin engine, crypto, mesh, services, and telephony packages are fully integrated in `android/app/src/main/java/com/mobiletracker`.
