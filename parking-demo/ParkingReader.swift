import Foundation
import Vision
import ImageIO

struct ParkingReading {
    let location: String
    let pillar: String
    let lines: [String]
    let scores: [Float]
    let canNotify: Bool
}

enum ParkingReader {
    static func matches(_ pattern: String, _ text: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: pattern)
        let range = NSRange(text.startIndex..., in: text)
        return regex.matches(in: text, range: range).compactMap {
            Range($0.range(at: 1), in: text).map { String(text[$0]) }
        }
    }
    static func parse(_ observations: [(String, Float)]) -> ParkingReading {
        var floors = [String:Float](), zones = [String:Float](), pillars = [String:Float]()
        var outdoor: Float?
        var directional = false
        let basement = #"(?<![A-Z0-9])B\s*([1-9][0-9]?)F?(?![A-Z0-9-])|지하\s*[1-9][0-9]?\s*층"#
        for (raw, score) in observations {
            let text = raw.uppercased().replacingOccurrences(of: "[‐‑–—]", with: "-", options: .regularExpression)
            if text.range(of: "출구|입구|방면|방향|EXIT|ENTRY|[↑↓←→↗↘↖↙↔]",options:.regularExpression) != nil { directional = true }
            for f in matches(#"(?<![A-Z0-9])B\s*([1-9][0-9]?)F?(?![A-Z0-9-])"#,text) + matches(#"지하\s*([1-9][0-9]?)\s*층"#,text) {
                let key = "지하 " + f + "층"; floors[key] = max(floors[key] ?? 0, score)
            }
            let above = text.replacingOccurrences(of: basement, with:" ", options:.regularExpression)
            let levels = matches(#"(?<![A-Z0-9-])([1-9][0-9]?)\s*(?:F(?![A-Z0-9])|층)"#, above)
                + matches(#"(?<![A-Z0-9])F\s*([1-9][0-9]?)(?![A-Z0-9-])"#,above)
            for f in levels {
                let key = "지상 " + f + "층"; floors[key] = max(floors[key] ?? 0, score)
            }
            if text.range(of:#"옥상|\b(?:RF|ROOFTOP)\b"#,options:.regularExpression) != nil {
                floors["옥상"] = max(floors["옥상"] ?? 0,score)
            }
            if text.range(of:#"야외|옥외|\bOUTDOOR\b"#,options:.regularExpression) != nil { outdoor = max(outdoor ?? 0,score) }
            let areaPattern = #"(?<![A-Z0-9])([A-Z]|[0-9]{1,2})\s*구역"#
            for zone in matches(areaPattern,text) + matches(#"(?:\bZONE|구역)\s*[:#]?\s*([A-Z]|[0-9]{1,2})(?![A-Z0-9-])"#,text) {
                zones[zone] = max(zones[zone] ?? 0,score)
            }
            // Require a hyphen or an explicit pillar label. Bare parking bay numbers are ambiguous.
            for v in matches(#"(?<![A-Z0-9])([A-Z]\s*-\s*[0-9]{1,3})(?![A-Z0-9])"#,text) +
                     matches(#"^\s*([AC-Z][0-9]{1,3})\s*$"#,text) +
                     matches(#"(?:기둥|PILLAR)\s*[:#]?\s*([A-Z]?[0-9]{1,3})(?![A-Z0-9])"#,text) +
                     matches(#"(?<![A-Z0-9])([0-9]{1,3})\s*번\s*기둥"#,text) {
                if text.range(of: #"^\s*F[1-9][0-9]?\s*$"#, options:.regularExpression) != nil { continue }
                let key = v.replacingOccurrences(of: " ", with: ""); pillars[key] = max(pillars[key] ?? 0, score)
            }
        }
        // If Vision separates "지하" from a number, a bare "2층" must not become "지상 2층".
        let separateBasement = observations.contains {
            ["지하", "지하층", "지하주차장"].contains($0.0.replacingOccurrences(of: " ", with: ""))
        }
        let mixedLevel = separateBasement && floors.keys.contains { $0.hasPrefix("지상") }
        let f = floors.count == 1 && !mixedLevel ? floors.keys.first : nil
        let z = zones.count == 1 ? zones.keys.first : nil
        let p = pillars.count == 1 ? pillars.keys.first : nil
        let place = [f ?? (outdoor != nil ? "야외" : nil), z.map { $0 + "구역" }]
            .compactMap { $0 }.joined(separator:" · ")
        // A marked outdoor zone is useful even where no pillar exists.
        let complete = (f != nil && (p != nil || z != nil)) ||
            (z != nil && (p != nil || outdoor != nil)) || (outdoor != nil && p != nil)
        let scores = [f.flatMap { floors[$0] }, z.flatMap { zones[$0] }, p.flatMap { pillars[$0] }, outdoor].compactMap { $0 }
        let compatible = !(f?.hasPrefix("지하") == true && outdoor != nil)
        let certain = !directional && !mixedLevel && compatible && floors.count <= 1 && zones.count <= 1 && pillars.count <= 1 &&
            scores.allSatisfy { $0 >= 0.65 }
        return ParkingReading(location:place, pillar:p ?? "", lines:observations.map { $0.0 },
                              scores:observations.map { $0.1 }, canNotify:complete && certain)
    }
    static func read(_ url: URL) throws -> ParkingReading {
        guard let source = CGImageSourceCreateWithURL(url as CFURL,nil),
              let frame = CGImageSourceCreateThumbnailAtIndex(source,0,[
                kCGImageSourceCreateThumbnailFromImageAlways:true,
                kCGImageSourceCreateThumbnailWithTransform:true,
                kCGImageSourceThumbnailMaxPixelSize:1800] as CFDictionary)
        else { throw NSError(domain:"ParkingDemo",code:1,userInfo:[NSLocalizedDescriptionKey:"사진을 읽을 수 없습니다. JPG, PNG 또는 HEIC 사진을 선택해 주세요."]) }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        let supported = try request.supportedRecognitionLanguages()
        request.recognitionLanguages = ["en-US", "ko-KR"].filter { supported.contains($0) }
        try VNImageRequestHandler(cgImage:frame,options:[:]).perform([request])
        return parse((request.results ?? []).compactMap { observation in
            observation.topCandidates(1).first.map { ($0.string,$0.confidence) }
        })
    }
}
