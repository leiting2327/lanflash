import AVFoundation
import SwiftUI

struct ScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    let onClose: () -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let vc = ScannerViewController()
        vc.onCode = onCode
        vc.onClose = onClose
        return vc
    }

    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {}
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    var onClose: (() -> Void)?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var configured = false
    private var handled = false
    private var hintLabel: UILabel?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        addChrome()
        authorizeAndConfigure()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [session] in
                session.stopRunning()
            }
        }
    }

    private func addChrome() {
        let close = UIButton(type: .system)
        close.setTitle("关闭", for: .normal)
        close.setTitleColor(.white, for: .normal)
        close.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        close.backgroundColor = UIColor(white: 0, alpha: 0.45)
        close.layer.cornerRadius = 16
        close.translatesAutoresizingMaskIntoConstraints = false
        close.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        view.addSubview(close)

        let hint = UILabel()
        hint.text = "将接收端的二维码放入取景框"
        hint.textColor = .white
        hint.font = .systemFont(ofSize: 14)
        hint.textAlignment = .center
        hint.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hint)
        hintLabel = hint

        NSLayoutConstraint.activate([
            close.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            close.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            close.widthAnchor.constraint(equalToConstant: 68),
            close.heightAnchor.constraint(equalToConstant: 34),
            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            hint.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -30),
        ])
    }

    @objc private func closeTapped() {
        onClose?()
    }

    private func authorizeAndConfigure() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.configureSession()
                    } else {
                        self?.showHint("请在系统设置中允许相机权限")
                    }
                }
            }
        default:
            showHint("请在系统设置中允许相机权限")
        }
    }

    private func showHint(_ text: String) {
        hintLabel?.text = text
    }

    private func configureSession() {
        guard !configured else { return }
        configured = true

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            showHint("无法使用相机")
            return
        }

        session.beginConfiguration()
        session.sessionPreset = .high
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
        }
        session.commitConfiguration()

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        previewLayer = layer

        DispatchQueue.global(qos: .userInitiated).async { [session] in
            session.startRunning()
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !handled,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else { return }
        handled = true
        session.stopRunning()
        onCode?(value)
        onClose?()
    }
}
