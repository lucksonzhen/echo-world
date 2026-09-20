import AppIntents
import Foundation
import UniformTypeIdentifiers

enum ScreenDescriptionMode: String, AppEnum {
    case brief, detailed, text

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "描述方式"
    static var caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .brief: "简要描述", .detailed: "详细描述", .text: "读出文字"
    ]
}

/// Receives the SYSTEM screenshot action's output. This intent cannot capture
/// other apps by itself, and must not open our UI before the screenshot action.
struct DescribeScreenIntent: AppIntent {
    static var title: LocalizedStringResource = "听见画面描述"
    static var description = IntentDescription("描述系统“截取屏幕截图”动作传入的图片，并返回可朗读文字。请先添加系统截屏动作，再添加本动作。不会自行读取其他 App 的屏幕。")
    static var openAppWhenRun: Bool = false
    static var authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @Parameter(title: "截图", supportedContentTypes: [.image],
               requestValueDialog: "请提供系统截屏动作的图片结果。",
               inputConnectionBehavior: .connectToPreviousIntentResult)
    var screenshot: IntentFile

    @Parameter(title: "描述方式", default: .brief)
    var mode: ScreenDescriptionMode

    @Parameter(title: "关于画面的问题", default: "", inputConnectionBehavior: .never)
    var question: String

    static var parameterSummary: some ParameterSummary {
        Summary("描述\(\.$screenshot)") {
            \.$mode
            \.$question
        }
    }

    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        try Task.checkCancellation()
        let configuration = try ConfigurationStore.read()
        guard configuration.allowsScreenshotUpload else {
            throw ScreenDescriptionError.message("请先打开听见屏幕，保存服务设置并允许发送截图进行描述。")
        }
        _ = try configuration.validatedURL()
        let image = try ScreenImageEncoder.jpeg(from: screenshot.data)
        try Task.checkCancellation()
        let style = DescriptionStyle(rawValue: mode.rawValue) ?? .brief
        let result = try await DescriptionClient(configuration: configuration).describe(jpeg: image, style: style, question: question)
        let spoken = result.spokenText(style: style)
        // The String also feeds a final system "Speak Text" action. ProvidesDialog
        // supplies Siri a response when this intent is the shortcut's last action.
        return .result(value: spoken, dialog: IntentDialog("\(spoken)"))
    }
}
