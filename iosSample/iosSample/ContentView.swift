import SwiftUI
import shared

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        BluetoothSample_iosKt.BluetoothSampleViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
