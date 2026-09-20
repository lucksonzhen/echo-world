import Foundation
import ImageIO
import UniformTypeIdentifiers

enum ScreenImageEncoder {
    static let maxUploadBytes = 2 * 1024 * 1024

    /// Downsample before decoding the full image, apply EXIF orientation, and
    /// strip source metadata by creating a new JPEG. No Photos or file writes.
    static func jpeg(from input: Data) throws -> Data {
        guard !input.isEmpty, input.count <= 25 * 1024 * 1024,
              let source = CGImageSourceCreateWithData(input as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) > 0 else {
            throw ScreenDescriptionError.message("没有收到有效截图。请把系统“截取屏幕截图”的结果连接到“听见画面描述”。")
        }
        for dimension in [1600, 1200, 960] {
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: dimension,
                kCGImageSourceShouldCacheImmediately: true
            ]
            guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
                throw ScreenDescriptionError.message("无法读取这张截图，请再次运行快捷指令。")
            }
            for quality in [0.82, 0.65, 0.48] {
                let output = NSMutableData()
                guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else { continue }
                CGImageDestinationAddImage(destination, thumbnail, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
                if CGImageDestinationFinalize(destination), output.length <= maxUploadBytes {
                    return output as Data
                }
            }
        }
        throw ScreenDescriptionError.message("截图压缩后仍然过大，请缩小屏幕上要描述的内容后重试。")
    }
}
