import SwiftUI
import Accessibility

@MainActor
final class SettingsModel: ObservableObject {
    @Published var configuration = ConnectionConfiguration()
    @Published var status = ""
    @Published var isChecking = false

    init() {
        do { configuration = try ConfigurationStore.read() }
        catch { status = error.localizedDescription }
    }

    func save() {
        do {
            _ = try configuration.validatedURL()
            try ConfigurationStore.save(configuration)
            status = "已保存。现在可以在快捷指令中添加听见画面描述。"
        } catch { status = error.localizedDescription }
    }

    func check() async {
        isChecking = true
        status = "正在检查服务连接和连接口令。"
        defer { isChecking = false }
        do {
            try await DescriptionClient(configuration: configuration).checkConnection()
            status = "服务可以连接，口令验证通过，服务端已填写 AI 密钥。请保存设置。"
        } catch { status = error.localizedDescription }
    }

    func clear() {
        do {
            try ConfigurationStore.clear()
            configuration = ConnectionConfiguration()
            status = "已清除设置并关闭截图上传。"
        } catch { status = error.localizedDescription }
    }

    func setUploadConsent(_ enabled: Bool) {
        if enabled {
            configuration.allowsScreenshotUpload = true
            status = "请保存设置，让截图上传授权生效。"
            return
        }
        // Revocation takes effect immediately. Preserve the saved address/token,
        // rather than accidentally persisting partially edited connection fields.
        do {
            var saved = try ConfigurationStore.read()
            saved.allowsScreenshotUpload = false
            try ConfigurationStore.save(saved)
            configuration.allowsScreenshotUpload = false
            status = "已关闭后续截图上传，无需再保存。已经发送的请求无法撤回。"
        } catch { status = error.localizedDescription }
    }
}

struct SettingsView: View {
    @StateObject private var model = SettingsModel()
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("在正在看的 App 里说“嘿 Siri，听见当前屏幕”，听取这一刻的画面描述。")
                        .font(.headline)
                    Text("首次使用需要按下面步骤创建一次快捷指令。以后不用下载图片、保存到相册或手动导入。")
                    Text("iPhone 使用 Siri 和系统快捷指令触发。本应用不提供跨 App 常驻悬浮按钮。")
                        .foregroundStyle(.secondary)
                }

                Section("连接描述服务") {
                    TextField("HTTPS 服务地址", text: $model.configuration.serviceURL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityLabel("描述服务地址")
                        .accessibilityHint("填写你部署的服务，例如 HTTPS 域名；无需填写斜杠 api。")
                    SecureField("连接口令，服务未启用时可留空", text: $model.configuration.accessToken)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityLabel("服务连接口令")
                    Toggle("允许在运行快捷指令时发送当前截图", isOn: Binding(
                        get: { model.configuration.allowsScreenshotUpload },
                        set: model.setUploadConsent
                    ))
                    Text("触发后，当前截图会压缩并发送到你设置的服务，由服务调用 AI 描述。截图可能含聊天或个人信息；只在你需要描述时触发。此 App 不保存截图或描述历史。")
                        .font(.footnote)
                    Button("保存设置", action: model.save)
                        .frame(minHeight: 44)
                    Button(model.isChecking ? "正在检查连接" : "检查连接") {
                        Task { await model.check() }
                    }
                    .disabled(model.isChecking)
                    .frame(minHeight: 44)
                    if !model.status.isEmpty {
                        Text(model.status)
                            .accessibilityLabel("连接状态：" + model.status)
                    }
                    #if DEBUG
                    Text("开发版本可使用局域网 HTTP 地址，例如 http://192.168.1.10:8787。电脑和 iPhone 需位于同一网络。正式版本要求 HTTPS。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    #endif
                }
                .disabled(model.isChecking)

                Section("只需设置一次") {
                    instruction(1, "打开系统“快捷指令”，新建快捷指令，命名为“听见当前屏幕”。")
                    instruction(2, "首先添加系统“截取屏幕截图”（Take Screenshot）动作。不要在它之前添加“打开 App”。")
                    instruction(3, "添加本 App 的“听见画面描述”动作，把“截图”参数设为上一步的截图结果。选择简要描述、详细描述或读出文字。")
                    instruction(4, "添加系统“朗读文本”（Speak Text）动作，输入选择“听见画面描述”的结果。保存即可。")
                    Button("打开快捷指令") {
                        if let url = URL(string: "shortcuts://") { openURL(url) }
                    }
                    .frame(minHeight: 44)
                    Text("也可以把同一快捷指令绑定到轻点背面或支持机型的操作按钮。第一次运行时，按系统提示允许必要的访问。")
                        .font(.footnote)
                }

                Section("在其他 App 中使用") {
                    Text("停留在要描述的图片或视频画面，说“嘿 Siri，听见当前屏幕”。每次只描述截屏时刻；视频可暂停后触发，不会分析完整视频或声音。")
                    Text("受保护的播放内容可能是黑屏；这种情况下无法得到有效画面。若 Siri 面板遮住目标内容，可改用轻点背面或操作按钮触发同一快捷指令。")
                    Text("朗读由系统执行，速度可在“朗读文本”动作中调整。如果你的系统重复朗读，可省略最后的“朗读文本”动作，仅使用 Siri 返回的描述；对实体按钮触发保留“朗读文本”。")
                        .font(.footnote)
                    Button("清除设置并关闭截图上传", role: .destructive, action: model.clear)
                        .frame(minHeight: 44)
                        .disabled(model.isChecking)
                }
            }
            .navigationTitle("听见屏幕")
            .onChange(of: model.status) { _, newValue in
                if !newValue.isEmpty { AccessibilityNotification.Announcement(newValue).post() }
            }
        }
    }

    private func instruction(_ number: Int, _ text: String) -> some View {
        Text("\(number). \(text)")
            .accessibilityLabel("第\(number)步。\(text)")
    }
}
