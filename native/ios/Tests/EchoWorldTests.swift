import XCTest
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
@testable import EchoWorld

final class EchoWorldTests: XCTestCase {
    func testPrivateHostValidationDoesNotDropInvalidLabels() {
        for host in ["127.0.0.1", "10.0.0.1", "192.168.0.20", "172.16.0.1", "172.31.255.1", "localhost", "[::1]", "mac.local"] {
            XCTAssertTrue(ConnectionConfiguration.isPrivateHost(host), host)
        }
        for host in ["example.com", "abc.127.0.0.1", "10.bad.1.2.3", "10..0.0.1", "10.0.0.1.", "127.0.0.999", "172.15.0.1", "172.32.0.1", "1.2.3.4", "10.+1.2.3"] {
            XCTAssertFalse(ConnectionConfiguration.isPrivateHost(host), host)
        }
    }

    func testServiceURLRejectsCredentialsAndAmbiguousSuffixes() throws {
        var configuration = ConnectionConfiguration(serviceURL: "https://example.com", accessToken: "", allowsScreenshotUpload: false)
        XCTAssertEqual(try configuration.validatedURL().host, "example.com")
        for invalid in ["", "example.com", "ftp://example.com", "https://user:secret@example.com", "https://example.com?token=secret", "https://example.com#api"] {
            configuration.serviceURL = invalid
            XCTAssertThrowsError(try configuration.validatedURL(), invalid)
        }
        configuration.serviceURL = "http://example.com"
        XCTAssertThrowsError(try configuration.validatedURL())
        configuration.serviceURL = "http://192.168.1.20:8787"
        #if DEBUG
        XCTAssertEqual(try configuration.validatedURL().port, 8787)
        #else
        XCTAssertThrowsError(try configuration.validatedURL())
        #endif
    }

    func testPayloadMatchesImageContractAndOmitsEmptyOptionalQuestion() throws {
        let payload = AnalysisPayload(mode: "brief", frames: [.init(dataUrl: "data:image/jpeg;base64,/9j/2Q==", timestampMs: 0)], question: nil)
        let data = try JSONEncoder().encode(payload)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["mediaType"] as? String, "image")
        XCTAssertEqual(json["mode"] as? String, "brief")
        XCTAssertNil(json["durationMs"])
        XCTAssertNil(json["question"])
        let frames = try XCTUnwrap(json["frames"] as? [[String: Any]])
        XCTAssertEqual(frames.count, 1)
        XCTAssertEqual(frames[0]["timestampMs"] as? Int, 0)
    }

    func testDescriptionIncludesUncertaintiesAndVisibleText() throws {
        let data = Data("""
        {"title":"咖啡菜单","summary":"屏幕中央是一张菜单。","details":["左边是饮品名称。"],"visibleText":["咖啡 20 元"],"timeline":[],"uncertainties":["底部小字无法辨认。"],"answer":null}
        """.utf8)
        let response = try JSONDecoder().decode(AnalysisResponse.self, from: data)
        let spoken = response.spokenText(style: .text)
        XCTAssertTrue(spoken.contains("咖啡 20 元"))
        XCTAssertTrue(spoken.contains("底部小字无法辨认"))
        XCTAssertFalse(spoken.contains("左边是饮品名称"))
        XCTAssertThrowsError(try JSONDecoder().decode(AnalysisResponse.self, from: Data("{\"summary\":\"缺少其他字段\"}".utf8)))
    }

    func testScreenshotIsDownsampledAndEncodedWithoutSourceMetadata() throws {
        let context = try XCTUnwrap(CGContext(data: nil, width: 2400, height: 1200,
            bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        context.setFillColor(CGColor(gray: 1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 2400, height: 1200))
        let cgImage = try XCTUnwrap(context.makeImage())
        let sourceData = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(sourceData, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, cgImage, [kCGImagePropertyExifDictionary: [kCGImagePropertyExifUserComment: "private metadata"]] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        let jpeg = try ScreenImageEncoder.jpeg(from: sourceData as Data)
        XCTAssertLessThanOrEqual(jpeg.count, ScreenImageEncoder.maxUploadBytes)
        XCTAssertEqual(Array(jpeg.prefix(2)), [0xff, 0xd8])
        let source = try XCTUnwrap(CGImageSourceCreateWithData(jpeg as CFData, nil))
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        XCTAssertEqual(properties[kCGImagePropertyPixelWidth] as? Int, 1600)
        XCTAssertEqual(properties[kCGImagePropertyPixelHeight] as? Int, 800)
        let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any]
        XCTAssertNil(exif?[kCGImagePropertyExifUserComment])
    }

    func testInvalidOrOversizeImagesAreRejectedBeforeNetworking() {
        XCTAssertThrowsError(try ScreenImageEncoder.jpeg(from: Data()))
        XCTAssertThrowsError(try ScreenImageEncoder.jpeg(from: Data("not an image".utf8)))
        XCTAssertThrowsError(try ScreenImageEncoder.jpeg(from: Data(repeating: 0, count: 25 * 1024 * 1024 + 1)))
    }

    func testDisabledUploadFailsBeforeTryingTheNetwork() async {
        do {
            _ = try await DescriptionClient(configuration: ConnectionConfiguration()).describe(jpeg: Data([1]), style: .brief, question: nil)
            XCTFail("Upload must require explicit consent")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("允许"))
        }
    }
}
