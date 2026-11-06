import Foundation
import CoreLocation
import BackgroundTasks

/**
 * iOS Location Engine & Sandbox Compliance Manager
 *
 * Navigates strict Apple iOS constraints:
 * 1. CoreTelephony Privacy Wall: LAC and CID are inaccessible to 3rd-party apps.
 *    The engine delegates sensor fusion to CoreLocation while enforcing power policies.
 * 2. Background Execution: Configures allowsBackgroundLocationUpdates,
 *    pausesLocationUpdatesAutomatically, and schedules BGAppRefreshTask.
 */
public final class IOSLocationManager: NSObject, CLLocationManagerDelegate {

    public static let shared = IOSLocationManager()
    public static let backgroundTaskIdentifier = "com.mobiletracker.bg.refresh"

    private let locationManager = CLLocationManager()
    private var isTrackingStationary = false

    public override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        locationManager.distanceFilter = 50.0 // meters
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = true
        locationManager.showsBackgroundLocationIndicator = false
    }

    public func requestPermissions() {
        locationManager.requestAlwaysAuthorization()
    }

    public func startAdaptiveTracking() {
        locationManager.startUpdatingLocation()
        locationManager.startMonitoringSignificantLocationChanges()
    }

    public func stopTracking() {
        locationManager.stopUpdatingLocation()
        locationManager.stopMonitoringSignificantLocationChanges()
    }

    // MARK: - CLLocationManagerDelegate

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let latestLocation = locations.last else { return }

        // Filter out cached or inaccurate fixes
        if latestLocation.horizontalAccuracy < 0 || latestLocation.horizontalAccuracy > 1000 {
            return
        }

        let speed = latestLocation.speed
        if speed < 0.5 && !isTrackingStationary {
            // Device has become stationary: relax accuracy to conserve battery
            isTrackingStationary = true
            locationManager.desiredAccuracy = kCLLocationAccuracyKilometer
            locationManager.distanceFilter = 200.0
        } else if speed >= 0.5 && isTrackingStationary {
            // Motion detected: restore hundred-meter tracking
            isTrackingStationary = false
            locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
            locationManager.distanceFilter = 50.0
        }

        // Forward sanitized coordinates to shared KMP state pipeline
        processLocationTelemetry(
            lat: latestLocation.coordinate.latitude,
            lon: latestLocation.coordinate.longitude,
            accuracy: latestLocation.horizontalAccuracy,
            timestamp: latestLocation.timestamp
        )
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("[IOSLocationManager] Location update failure: \(error.localizedDescription)")
    }

    // MARK: - Background Task Management (BGTaskScheduler)

    public func registerBackgroundTasks() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.backgroundTaskIdentifier,
            using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else { return }
            self.handleAppRefresh(task: refreshTask)
        }
    }

    public func scheduleNextBackgroundRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: Self.backgroundTaskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60) // 15-minute interval
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            print("[IOSLocationManager] BGTask submission error: \(error.localizedDescription)")
        }
    }

    private func handleAppRefresh(task: BGAppRefreshTask) {
        scheduleNextBackgroundRefresh()

        task.expirationHandler = {
            // Clean up and abort network sync
            print("[IOSLocationManager] BGTask expired before full sync")
        }

        // Perform opportunistic sync with cloud or local cache
        DispatchQueue.global().async {
            // Flush offline location outbox to cloud backend
            task.setTaskCompleted(success: true)
        }
    }

    private func processLocationTelemetry(lat: Double, lon: Double, accuracy: Double, timestamp: Date) {
        // Bridges to KMP / SQLite database layer
    }
}
