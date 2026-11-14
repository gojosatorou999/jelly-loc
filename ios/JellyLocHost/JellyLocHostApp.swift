import CoreLocation
import SwiftUI

/// Minimal host app for Xcode location simulation.
///
/// iOS does not let apps fake the device location for other apps. The supported route is Xcode:
/// while this app runs from Xcode with "Simulate Location" set, iOS reports that location to every app
/// (Maps, WhatsApp, ...). This screen just shows what iOS currently reports so you can confirm it worked.
@main
struct JellyLocHostApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

final class LocationReader: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var location: CLLocation?
    @Published var status: CLAuthorizationStatus = .notDetermined

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        status = manager.authorizationStatus
    }

    func start() {
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
        manager.startUpdatingLocation()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        status = manager.authorizationStatus
        if status == .authorizedWhenInUse || status == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        location = locations.last
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Ignore transient errors; the next update will arrive when a location is available.
    }
}

struct ContentView: View {
    @StateObject private var reader = LocationReader()

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "location.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(.teal)

            Text("Jelly Loc")
                .font(.largeTitle.bold())

            if let loc = reader.location {
                VStack(spacing: 6) {
                    Text("iOS is reporting")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text(String(format: "%.6f, %.6f", loc.coordinate.latitude, loc.coordinate.longitude))
                        .font(.title3.monospacedDigit())
                    Text("±\(Int(loc.horizontalAccuracy)) m")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let url = URL(string: "https://maps.apple.com/?ll=\(loc.coordinate.latitude),\(loc.coordinate.longitude)") {
                    Link("Open in Apple Maps", destination: url)
                }
            } else if reader.status == .denied || reader.status == .restricted {
                Text("Location access is off.\nSettings → Privacy & Security → Location Services → Jelly Loc.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            } else {
                ProgressView("Waiting for location…")
            }

            Text("Keep this app running from Xcode. Change the location in Xcode with Debug → Simulate Location.")
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal)
        }
        .padding()
        .onAppear { reader.start() }
    }
}
