import Foundation

enum DescriptionStyle: String, Codable, Sendable {
    case brief, detailed, text
}

struct AnalysisPayload: Encodable, Sendable {
    struct Frame: Encodable, Sendable {
        let dataUrl: String
        let timestampMs: Int
    }
    let mediaType = "image"
    let mode: String
    let frames: [Frame]
    let question: String?
}

/// Mirrors shared/contracts.ts. Keeping the full response catches an incompatible
/// backend before incomplete text can be presented as a successful description.
struct AnalysisResponse: Decodable, Sendable {
    struct TimelineEntry: Decodable, Sendable {
        let timestampMs: Int
        let description: String
    }
    let title: String
    let summary: String
    let details: [String]
    let visibleText: [String]
    let timeline: [TimelineEntry]
    let uncertainties: [String]
    let answer: String?

    func spokenText(style: DescriptionStyle) -> String {
        var segments = [title, summary]
        if let answer, !answer.isEmpty { segments.append("关于你的问题：" + answer) }
        if style == .text {
            segments.append(visibleText.isEmpty ? "没有识别到清晰可读的文字。" : "画面中的文字：" + visibleText.joined(separator: "。"))
        } else {
            segments.append(contentsOf: details)
            if !visibleText.isEmpty { segments.append("画面中的文字：" + visibleText.joined(separator: "。")) }
        }
        if !uncertainties.isEmpty { segments.append("需要说明：" + uncertainties.joined(separator: "。")) }
        return segments.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.joined(separator: "\n")
    }
}

private struct ServiceFailure: Decodable { let error: String; let code: String }
private struct ServiceHealth: Decodable { let status: String; let configured: Bool }

/// Never follow redirects with screenshots or a bearer token.
private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

struct DescriptionClient {
    let configuration: ConnectionConfiguration

    func checkConnection() async throws {
        let data = try await send(path: "api/health")
        guard let health = try? JSONDecoder().decode(ServiceHealth.self, from: data), health.status == "ok" else {
            throw ScreenDescriptionError.message("这个地址未返回可识别的描述服务，请检查服务地址。")
        }
        guard health.configured else {
            throw ScreenDescriptionError.message("服务可以连接，但服务端尚未配置 AI 密钥。")
        }
    }

    func describe(jpeg: Data, style: DescriptionStyle, question: String?) async throws -> AnalysisResponse {
        guard configuration.allowsScreenshotUpload else {
            throw ScreenDescriptionError.message("请先打开听见屏幕，允许在运行快捷指令时发送当前截图进行描述。")
        }
        guard !jpeg.isEmpty, jpeg.count <= ScreenImageEncoder.maxUploadBytes else {
            throw ScreenDescriptionError.message("截图大小不正确，请重新运行快捷指令。")
        }
        let trimmedQuestion = question?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (trimmedQuestion?.count ?? 0) <= 500 else {
            throw ScreenDescriptionError.message("问题过长，请使用 500 字以内的问题。")
        }
        let payload = AnalysisPayload(
            mode: style.rawValue,
            frames: [.init(dataUrl: "data:image/jpeg;base64," + jpeg.base64EncodedString(), timestampMs: 0)],
            question: trimmedQuestion?.isEmpty == false ? trimmedQuestion : nil
        )
        let data = try await send(path: "api/describe", body: JSONEncoder().encode(payload))
        guard let result = try? JSONDecoder().decode(AnalysisResponse.self, from: data),
              !result.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              result.timeline.isEmpty else {
            throw ScreenDescriptionError.message("服务返回的描述不完整，请重新运行快捷指令。")
        }
        return result
    }

    private func send(path: String, body: Data? = nil) async throws -> Data {
        try Task.checkCancellation()
        let base = try configuration.validatedURL()
        var request = URLRequest(url: base.appendingPathComponent(path))
        request.httpMethod = body == nil ? "GET" : "POST"
        request.httpBody = body
        request.timeoutInterval = 70
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let token = configuration.accessToken.trimmingCharacters(in: .whitespacesAndNewlines)
        if !token.isEmpty { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }

        let sessionConfiguration = URLSessionConfiguration.ephemeral
        sessionConfiguration.timeoutIntervalForRequest = 70
        sessionConfiguration.timeoutIntervalForResource = 80
        sessionConfiguration.urlCache = nil
        sessionConfiguration.httpCookieStorage = nil
        let session = URLSession(configuration: sessionConfiguration, delegate: NoRedirectDelegate(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }

        do {
            let (data, response) = try await session.data(for: request)
            try Task.checkCancellation()
            guard let response = response as? HTTPURLResponse else {
                throw ScreenDescriptionError.message("描述服务未返回有效回应，请检查连接。")
            }
            guard (200...299).contains(response.statusCode) else {
                if response.statusCode == 401 { throw ScreenDescriptionError.message("连接口令不正确，请打开听见屏幕检查设置。") }
                if response.statusCode == 429 { throw ScreenDescriptionError.message("请求较频繁，请稍等一分钟再试。") }
                if (300...399).contains(response.statusCode) { throw ScreenDescriptionError.message("服务地址发生跳转，请在设置中填写最终服务地址。") }
                if let failure = try? JSONDecoder().decode(ServiceFailure.self, from: data) {
                    throw ScreenDescriptionError.message(String(failure.error.prefix(300)))
                }
                throw ScreenDescriptionError.message("描述服务暂时不可用，请稍后再试。")
            }
            guard data.count <= 1024 * 1024 else { throw ScreenDescriptionError.message("返回的描述过大，请重试。") }
            return data
        } catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            if error.code == .timedOut { throw ScreenDescriptionError.message("描述超时，请稍后重新运行快捷指令。") }
            throw ScreenDescriptionError.message("无法连接描述服务，请检查网络和听见屏幕中的服务地址。")
        }
    }
}
