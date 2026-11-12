// 녹화한 화면(JPEG)과 음성 트랙을 합쳐 1080p MP4를 만듭니다. macOS 기본 기능(AVFoundation)만 씁니다.
// 사용법: swiftc -swift-version 5 -O compose.swift -o compose && ./compose <작업 폴더> <결과.mp4>
import AVFoundation
import CoreGraphics
import Foundation
import ImageIO

struct Frame: Decodable { let f: String; let t: Double }
struct Manifest: Decodable { let frames: [Frame]; let keeps: [[KeepItem]] }
enum KeepItem: Decodable {
    case state(String), time(Double)
    init(from d: Decoder) throws {
        let c = try d.singleValueContainer()
        if let s = try? c.decode(String.self) { self = .state(s) } else { self = .time(try c.decode(Double.self)) }
    }
}

let work = URL(fileURLWithPath: CommandLine.arguments[1])
let outURL = URL(fileURLWithPath: CommandLine.arguments[2])
let m = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: work.appendingPathComponent("manifest.json")))

var ranges: [(Double, Double)] = []
var on = 0.0
for k in m.keeps {
    guard case .state(let s) = k[0], case .time(let ms) = k[1] else { continue }
    if s == "on" { on = ms / 1000 } else { ranges.append((on, ms / 1000)) }
}
let FPS = 30, W = 1920, H = 1080
let videoOnly = work.appendingPathComponent("video_only.mov")
try? FileManager.default.removeItem(at: videoOnly)

let writer = try AVAssetWriter(outputURL: videoOnly, fileType: .mov)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: W, AVVideoHeightKey: H,
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 12_000_000, AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel],
])
input.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA, kCVPixelBufferWidthKey as String: W, kCVPixelBufferHeightKey as String: H,
])
writer.add(input)
writer.startWriting()
writer.startSession(atSourceTime: .zero)

func draw(_ path: URL, into buffer: CVPixelBuffer) {
    guard let src = CGImageSourceCreateWithURL(path as CFURL, nil), let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return }
    CVPixelBufferLockBaseAddress(buffer, [])
    defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
    let ctx = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: W, height: H, bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)!
    ctx.interpolationQuality = .high
    ctx.draw(img, in: CGRect(x: 0, y: 0, width: W, height: H))
}

var outIndex = 0, frameCursor = 0
for (a, b) in ranges {
    var t = a
    while t < b {
        while frameCursor + 1 < m.frames.count && m.frames[frameCursor + 1].t <= t { frameCursor += 1 }
        while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.002) }
        var pb: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &pb)
        draw(work.appendingPathComponent("frames/\(m.frames[frameCursor].f)"), into: pb!)
        if !adaptor.append(pb!, withPresentationTime: CMTime(value: CMTimeValue(outIndex), timescale: CMTimeScale(FPS))) {
            fatalError("프레임 쓰기 실패: \(String(describing: writer.error))")
        }
        outIndex += 1
        t = a + Double(outIndex) / Double(FPS) - ranges.prefix { $0 != (a, b) }.reduce(0) { $0 + ($1.1 - $1.0) }
    }
}
input.markAsFinished()
let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }
done.wait()
guard writer.status == .completed else { fatalError("영상 쓰기 실패: \(String(describing: writer.error))") }

// 음성과 합치기
let mix = AVMutableComposition()
let videoAsset = AVURLAsset(url: videoOnly), audioAsset = AVURLAsset(url: work.appendingPathComponent("voice.m4a"))
let duration = videoAsset.duration
try mix.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)!
    .insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: videoAsset.tracks(withMediaType: .video)[0], at: .zero)
let audioDuration = CMTimeMinimum(duration, audioAsset.duration)
try mix.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
    .insertTimeRange(CMTimeRange(start: .zero, duration: audioDuration), of: audioAsset.tracks(withMediaType: .audio)[0], at: .zero)
try? FileManager.default.removeItem(at: outURL)
let export = AVAssetExportSession(asset: mix, presetName: AVAssetExportPresetPassthrough)!
export.outputURL = outURL
export.outputFileType = .mp4
let exported = DispatchSemaphore(value: 0)
export.exportAsynchronously { exported.signal() }
exported.wait()
guard export.status == .completed else { fatalError("합치기 실패: \(String(describing: export.error))") }
print("완성: \(outURL.path) · \(String(format: "%.1f", duration.seconds))초, \(outIndex)프레임")
