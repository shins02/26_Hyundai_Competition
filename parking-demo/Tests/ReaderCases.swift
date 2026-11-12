import Foundation

@main
struct ReaderCases {
    static func main() {
        typealias Case = ([String], String, String, Bool)
        let cases: [Case] = [
            (["지상 3층", "A-08"], "지상 3층", "A-08", true),
            (["F3", "A-08"], "지상 3층", "A-08", true),
            (["옥상", "C-12"], "옥상", "C-12", true),
            (["지하 2층", "C-17"], "지하 2층", "C-17", true),
            (["B2F", "C-17"], "지하 2층", "C-17", true),
            (["야외", "D구역"], "야외 · D구역", "", true),
            (["ZONE D", "D-12"], "D구역", "D-12", true),
            (["야외", "D구역", "E구역", "D-12"], "야외", "D-12", false),
            (["B2", "3F", "C-17"], "", "C-17", false),
            (["지하", "2층", "C-17"], "", "C-17", false),
            (["3F", "출구 →", "A-08"], "지상 3층", "A-08", false),
            (["D-12"], "", "D-12", false)
        ]
        for (lines, label, marker, notify) in cases {
            let r = ParkingReader.parse(lines.map { ($0, 1.0) })
            precondition(r.location == label && r.pillar == marker && r.canNotify == notify, "Unexpected result for \(lines)")
        }
        print("\(cases.count) recognition cases passed")
    }
}
