import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

extension Color {
    init(hex: UInt32) {
        self.init(UIColor(hex: hex))
    }
}

private let bg = Color(hex: 0xF6F7FB)
private let ink = Color(hex: 0x12141A)
private let sub = Color(hex: 0x6B7280)
private let primary = Color(hex: 0x2F6BFF)
private let card = Color.white

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @State private var screen: Screen = .home

    enum Screen { case home, send, receive, activate }

    var body: some View {
        ZStack {
            bg.ignoresSafeArea()
            if model.licenseConfigured && model.license == nil {
                ActivationView(canGoBack: false, onBack: {})
            } else {
                switch screen {
                case .home:
                    HomeView(
                        onSend: { screen = .send },
                        onReceive: { screen = .receive },
                        onActivate: { screen = .activate }
                    )
                case .send:
                    SendView(sender: model.sender, onBack: { screen = .home })
                case .receive:
                    ReceiveView(receiver: model.receiver, onBack: { screen = .home })
                case .activate:
                    ActivationView(canGoBack: true, onBack: { screen = .home })
                }
            }
        }
    }
}

struct HomeView: View {
    @EnvironmentObject var model: AppModel
    let onSend: () -> Void
    let onReceive: () -> Void
    let onActivate: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 13, style: .continuous)
                            .fill(LinearGradient(colors: [Color(hex: 0x2F6BFF), Color(hex: 0x7A5CFF)], startPoint: .topLeading, endPoint: .bottomTrailing))
                            .frame(width: 42, height: 42)
                        Image(systemName: "bolt.fill").foregroundColor(.white)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text("闪电传").font(.system(size: 24, weight: .bold)).foregroundColor(ink)
                        Text("局域网直连 · 多通道极速传输").font(.system(size: 12)).foregroundColor(sub)
                    }
                    Spacer()
                }
                .padding(.top, 18)

                VStack(alignment: .leading, spacing: 8) {
                    Text("跑满你的局域网")
                        .font(.system(size: 21, weight: .bold))
                        .foregroundColor(.white)
                    Text("4 路并发 TCP + 块级校验，手机与电脑之间不经过云端。")
                        .font(.system(size: 13))
                        .foregroundColor(.white.opacity(0.85))
                    HStack(spacing: 8) {
                        chip("多线程")
                        chip("CRC 校验")
                        chip("扫码配对")
                    }
                    .padding(.top, 6)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
                .background(
                    LinearGradient(colors: [Color(hex: 0x2F6BFF), Color(hex: 0x7A5CFF)], startPoint: .topLeading, endPoint: .bottomTrailing)
                )
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))

                actionCard(
                    icon: "paperplane.fill",
                    title: "发送文件",
                    desc: "扫描接收端二维码，选择文件后立即开始传输",
                    action: onSend
                )

                actionCard(
                    icon: "arrow.down.circle.fill",
                    title: "接收文件",
                    desc: "展示本机配对二维码，等待对方连接并接收",
                    action: onReceive
                )

                licenseChip

                Text("闪电传 v\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0")")
                    .font(.system(size: 11))
                    .foregroundColor(Color(hex: 0xB3BAC7))
                    .padding(.top, 8)
                    .padding(.bottom, 24)
            }
            .padding(.horizontal, 20)
        }
    }

    private func chip(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11))
            .foregroundColor(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Color.white.opacity(0.18))
            .clipShape(Capsule())
    }

    private func actionCard(icon: String, title: String, desc: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color(hex: 0xEDF2FF))
                        .frame(width: 50, height: 50)
                    Image(systemName: icon)
                        .font(.system(size: 21, weight: .medium))
                        .foregroundColor(primary)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.system(size: 17, weight: .semibold)).foregroundColor(ink)
                    Text(desc)
                        .font(.system(size: 12))
                        .foregroundColor(sub)
                        .multilineTextAlignment(.leading)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Color(hex: 0xB9C0CC))
            }
            .padding(18)
            .background(card)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private var licenseChip: some View {
        HStack(spacing: 8) {
            Image(systemName: model.license != nil ? "checkmark.seal.fill" : "key.fill")
                .foregroundColor(model.license != nil ? Color(hex: 0x16A34A) : sub)
            Text(licenseText)
                .font(.system(size: 12))
                .foregroundColor(sub)
            Spacer()
            if model.licenseConfigured {
                Button(model.license == nil ? "去激活" : "更换激活码", action: onActivate)
                    .font(.system(size: 12))
                    .foregroundColor(primary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(card)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var licenseText: String {
        if !model.licenseConfigured { return "开发模式：未配置授权服务" }
        if let info = model.license {
            let days = max(0, Int(info.expiresAt.timeIntervalSinceNow / 86_400))
            return "已激活 · \(info.plan.uppercased()) · 剩余 \(days) 天"
        }
        return "未激活"
    }
}

struct SendView: View {
    @ObservedObject var sender: Sender
    let onBack: () -> Void

    @State private var pair: PairInfo?
    @State private var urls: [URL] = []
    @State private var scanning = false
    @State private var importing = false
    @State private var scanError: String?

    var body: some View {
        VStack(spacing: 0) {
            header("发送文件", onBack: onBack)
            ScrollView {
                VStack(spacing: 14) {
                    targetCard
                    if let scanError {
                        Text(scanError).font(.system(size: 12)).foregroundColor(Color(hex: 0xDC2626)).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    filesCard
                    sendStatus
                }
                .padding(20)
                .padding(.bottom, 24)
            }
        }
        .fullScreenCover(isPresented: $scanning) {
            ScannerView(
                onCode: { code in
                    scanning = false
                    if let decoded = PairInfo.decode(code) {
                        pair = decoded
                        scanError = nil
                        sender.reset()
                    } else {
                        scanError = "二维码不是有效的闪电传配对码"
                    }
                },
                onClose: { scanning = false }
            )
            .ignoresSafeArea()
        }
        .fileImporter(
            isPresented: $importing,
            allowedContentTypes: [.item, .data],
            allowsMultipleSelection: true
        ) { result in
            if case .success(let picked) = result {
                urls = picked
                sender.reset()
            }
        }
    }

    private var targetCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("接收端").font(.system(size: 13)).foregroundColor(sub)
            if let pair {
                Text(pair.name).font(.system(size: 17, weight: .semibold)).foregroundColor(ink)
                Text("\(pair.ips.first ?? "-") : \(pair.port)")
                    .font(.system(size: 12))
                    .foregroundColor(sub)
                Button("重新扫描") { scanning = true }
                    .font(.system(size: 12))
            } else {
                Text("还没有配对").font(.system(size: 17, weight: .semibold)).foregroundColor(ink)
                Button {
                    scanning = true
                } label: {
                    HStack {
                        Image(systemName: "qrcode.viewfinder")
                        Text("扫描二维码")
                    }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(primary)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(card)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var filesCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("文件").font(.system(size: 13)).foregroundColor(sub)
                Spacer()
                Button(urls.isEmpty ? "选择文件" : "重新选择") { importing = true }
                    .font(.system(size: 12))
                    .foregroundColor(primary)
            }
            if urls.isEmpty {
                Text("尚未选择文件").font(.system(size: 14)).foregroundColor(sub)
            } else {
                Text("已选择 \(urls.count) 个文件")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(ink)
                ForEach(urls.prefix(8), id: \.self) { url in
                    HStack(spacing: 6) {
                        Image(systemName: "doc.fill")
                            .font(.system(size: 12))
                            .foregroundColor(Color(hex: 0x9AA3B2))
                        Text(url.lastPathComponent)
                            .font(.system(size: 12))
                            .foregroundColor(sub)
                            .lineLimit(1)
                    }
                }
                if urls.count > 8 {
                    Text("… 还有 \(urls.count - 8) 个")
                        .font(.system(size: 12))
                        .foregroundColor(sub)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(card)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var sendStatus: some View {
        VStack(spacing: 12) {
            Button {
                if let pair { sender.send(pair: pair, urls: urls) }
            } label: {
                HStack(spacing: 8) {
                    if sender.sending {
                        ProgressView().progressViewStyle(.circular).tint(.white)
                        Text("正在发送 · \(Proto.formatSpeed(sender.progress.speed))")
                    } else {
                        Image(systemName: "paperplane.fill")
                        Text("开始发送")
                    }
                }
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .background(pair != nil && !urls.isEmpty && !sender.sending ? primary : Color(hex: 0xB9C6E8))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .disabled(pair == nil || urls.isEmpty || sender.sending)

            if sender.sending || (sender.progress.totalBytes > 0 && !sender.done) {
                let fraction = sender.progress.totalBytes > 0
                    ? Double(sender.progress.sentBytes) / Double(sender.progress.totalBytes)
                    : 0
                ProgressView(value: min(max(fraction, 0), 1))
                    .tint(primary)
                HStack {
                    Text("\(Proto.formatBytes(sender.progress.sentBytes)) / \(Proto.formatBytes(sender.progress.totalBytes))")
                    Spacer()
                    Text(Proto.formatSpeed(sender.progress.speed))
                }
                .font(.system(size: 12))
                .foregroundColor(sub)
            }

            if sender.done {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill").foregroundColor(Color(hex: 0x16A34A))
                    Text("发送完成，接收端已校验全部数据")
                        .font(.system(size: 13))
                        .foregroundColor(Color(hex: 0x16A34A))
                    Spacer()
                }
            }

            if let error = sender.error {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 13))
                        .foregroundColor(Color(hex: 0xDC2626))
                    Text(error)
                        .font(.system(size: 12))
                        .foregroundColor(Color(hex: 0xDC2626))
                        .multilineTextAlignment(.leading)
                    Spacer()
                }
            }
        }
    }
}

struct ReceiveView: View {
    @ObservedObject var receiver: Receiver
    let onBack: () -> Void

    @State private var qr: UIImage?
    @State private var shareItem: ShareItem?

    var body: some View {
        VStack(spacing: 0) {
            header("接收文件", onBack: onBack)
            ScrollView {
                VStack(spacing: 16) {
                    if receiver.state.waiting && !receiver.state.finished {
                        qrCard
                    } else {
                        progressCard
                    }
                    if !receiver.state.files.isEmpty {
                        filesCard
                    }
                }
                .padding(20)
                .padding(.bottom, 24)
            }
        }
        .onAppear {
            receiver.start()
            qr = makeQRImage(receiver.state.pairText)
        }
        .onDisappear { receiver.stop() }
        .onChange(of: receiver.state.pairText) { text in
            qr = makeQRImage(text)
        }
        .sheet(item: $shareItem) { item in
            ShareSheet(items: [item.url])
        }
    }

    private var qrCard: some View {
        VStack(spacing: 12) {
            Text("用另一台设备扫码")
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(ink)
            Text("发送端打开「发送文件」并扫描此码")
                .font(.system(size: 12))
                .foregroundColor(sub)
            Group {
                if let qr {
                    Image(uiImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                } else {
                    ProgressView()
                }
            }
            .frame(width: 230, height: 230)
            .padding(10)
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

            Text("\(receiver.state.ips.first ?? "未连接 Wi-Fi") : \(receiver.state.port)")
                .font(.system(size: 12))
                .foregroundColor(sub)
            Text("请保持此页面打开 · 两台设备需在同一 Wi-Fi")
                .font(.system(size: 11))
                .foregroundColor(Color(hex: 0x9AA3B2))
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(card)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    private var progressCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: receiver.state.finished ? "checkmark.circle.fill" : "arrow.down.circle.fill")
                    .foregroundColor(receiver.state.finished ? Color(hex: 0x16A34A) : primary)
                Text(receiver.state.finished ? "传输完成" : "正在接收文件")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(ink)
                Spacer()
            }
            let fraction = receiver.state.total > 0
                ? Double(receiver.state.received) / Double(receiver.state.total)
                : 0
            ProgressView(value: receiver.state.finished ? 1 : min(max(fraction, 0), 1))
                .tint(receiver.state.finished ? Color(hex: 0x16A34A) : primary)
            HStack {
                Text("\(Proto.formatBytes(receiver.state.received)) / \(Proto.formatBytes(receiver.state.total))")
                Spacer()
                if !receiver.state.finished {
                    Text(Proto.formatSpeed(receiver.state.speed))
                }
            }
            .font(.system(size: 12))
            .foregroundColor(sub)
        }
        .padding(20)
        .background(card)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    private var filesCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(receiver.state.finished ? "已保存到「文件」App / 闪电传" : "正在接收")
                .font(.system(size: 13))
                .foregroundColor(sub)
            ForEach(receiver.state.files) { file in
                HStack(spacing: 8) {
                    Image(systemName: "doc.fill")
                        .font(.system(size: 13))
                        .foregroundColor(Color(hex: 0x9AA3B2))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(file.name)
                            .font(.system(size: 13))
                            .foregroundColor(ink)
                            .lineLimit(1)
                        Text(Proto.formatBytes(file.size))
                            .font(.system(size: 11))
                            .foregroundColor(sub)
                    }
                    Spacer()
                    if receiver.state.finished {
                        Button {
                            shareItem = ShareItem(url: file.path)
                        } label: {
                            Image(systemName: "square.and.arrow.up")
                                .foregroundColor(primary)
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(card)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

struct ActivationView: View {
    @EnvironmentObject var model: AppModel
    let canGoBack: Bool
    let onBack: () -> Void

    @State private var code = ""

    var body: some View {
        VStack(spacing: 0) {
            if canGoBack {
                header("激活", onBack: onBack)
            } else {
                Spacer().frame(height: 70)
            }
            ScrollView {
                VStack(spacing: 16) {
                    ZStack {
                        Circle()
                            .fill(Color(hex: 0xEDF2FF))
                            .frame(width: 74, height: 74)
                        Image(systemName: "key.fill")
                            .font(.system(size: 30))
                            .foregroundColor(primary)
                    }
                    Text("激活闪电传")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(ink)
                    Text("输入激活码后将绑定当前设备，激活后可长期使用")
                        .font(.system(size: 13))
                        .foregroundColor(sub)
                        .multilineTextAlignment(.center)

                    TextField("LF-XXXX-XXXX-XXXX", text: $code)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding(14)
                        .background(card)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .stroke(Color(hex: 0xDDE3EE), lineWidth: 1)
                        )
                        .padding(.top, 6)

                    Button {
                        model.activate(code)
                    } label: {
                        HStack(spacing: 8) {
                            if model.activating {
                                ProgressView().progressViewStyle(.circular).tint(.white)
                                Text("正在激活")
                            } else {
                                Text("立即激活")
                            }
                        }
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(model.licenseConfigured && !code.isEmpty ? primary : Color(hex: 0xB9C6E8))
                        .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                    }
                    .disabled(!model.licenseConfigured || code.isEmpty || model.activating)

                    if let error = model.licenseError {
                        Text(error)
                            .font(.system(size: 12))
                            .foregroundColor(Color(hex: 0xDC2626))
                            .multilineTextAlignment(.center)
                    }

                    if !model.licenseConfigured {
                        Text("当前版本未配置授权服务地址，请联系开发者。")
                            .font(.system(size: 12))
                            .foregroundColor(Color(hex: 0xB45309))
                            .multilineTextAlignment(.center)
                    }

                    VStack(spacing: 3) {
                        Text("设备标识").font(.system(size: 11)).foregroundColor(Color(hex: 0x9AA3B2))
                        Text(model.deviceId).font(.system(size: 11)).foregroundColor(Color(hex: 0x9AA3B2))
                    }
                    .padding(.top, 14)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 30)
            }
        }
    }
}

struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

private func header(_ title: String, onBack: @escaping () -> Void) -> some View {
    HStack(spacing: 4) {
        Button(action: onBack) {
            Image(systemName: "chevron.left")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(ink)
                .frame(width: 40, height: 40)
        }
        Text(title).font(.system(size: 19, weight: .semibold)).foregroundColor(ink)
        Spacer()
    }
    .padding(.horizontal, 8)
    .padding(.top, 4)
}

private func makeQRImage(_ text: String) -> UIImage? {
    guard !text.isEmpty else { return nil }
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(text.utf8)
    filter.correctionLevel = "M"
    guard let output = filter.outputImage else { return nil }
    let scale: CGFloat = 12
    let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
    guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
    return UIImage(cgImage: cg)
}
