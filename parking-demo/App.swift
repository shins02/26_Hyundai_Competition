import AppKit
import SwiftUI
import UniformTypeIdentifiers

struct Receipt: Codable {
    let floor: String
    let pillar: String
    let time: Date
}
@MainActor
final class Demo: ObservableObject {
    enum Gear: String { case drive = "D", park = "P" }
    @Published var gear = Gear.drive
    @Published var imageURL: URL?
    @Published var preview: NSImage?
    @Published var busy = false
    @Published var floor = ""
    @Published var pillar = ""
    @Published var feedback = ""
    @Published var raw = ""
    @Published var receipt: Receipt?
    @Published var sample = 0
    private var generation = 0
    private let key = "parking-receipt-v1"

    init() {
        if let data = UserDefaults.standard.data(forKey:key) { receipt = try? JSONDecoder().decode(Receipt.self,from:data) }
        selectSample(3)
    }
    func selectSample(_ n: Int) {
        guard let url = Bundle.main.url(forResource:String(format:"%02d",n+1),withExtension:"jpg",subdirectory:"Samples") else { return }
        load(url, sample:n)
    }
    func load(_ url: URL, sample n:Int) {
        generation += 1; busy = false; gear = .drive
        sample = n; imageURL = url; preview = NSImage(contentsOf:url); floor = ""; pillar = ""; raw = ""
        feedback = "주차를 완료하면 이 사진에서 층·구역과 위치 표지를 읽습니다."
    }
    func pick() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.jpeg,.png,.heic]; panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url { load(url,sample:-1) }
    }
    func park() {
        guard let url = imageURL, gear == .drive else { return }
        gear = .park; busy = true; feedback = "표지판 글자를 읽고 있어요…"
        let job = generation
        Task {
            let result = await Task.detached { Result { try ParkingReader.read(url) } }.value
            guard job == generation else { return }
            busy = false
            switch result {
            case .success(let reading):
                floor = reading.location; pillar = reading.pillar; raw = reading.lines.joined(separator:" · ")
                if reading.canNotify { save() }
                else { feedback = "위치를 확정하기 어려워요. 사진을 보고 층이나 구역과 위치 표지를 확인해 주세요." }
            case .failure:
                feedback = "글자를 읽지 못했어요. 사진을 확인하고 위치를 입력해 주세요."
            }
        }
    }
    var ready: Bool { gear == .park && !busy && !floor.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty && (!pillar.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty || floor.contains("구역")) }
    func save() {
        guard ready else { return }
        let new = Receipt(floor:floor.trimmingCharacters(in:.whitespacesAndNewlines),pillar:pillar.trimmingCharacters(in:.whitespacesAndNewlines),time:.now)
        receipt = new
        if let data = try? JSONEncoder().encode(new) { UserDefaults.standard.set(data,forKey:key) }
        feedback = "휴대폰 모의 알림에 주차 위치를 저장했어요."
    }
    func clearReceipt() { receipt = nil; UserDefaults.standard.removeObject(forKey:key) }
}

struct DemoView: View {
    static let sampleNames = ["지하 2층", "지하 3층", "기둥 미표시", "지상 주차타워", "야외 주차장"]
    @StateObject private var demo = Demo()
    let mint = Color(red:0.54,green:0.91,blue:0.78)
    var body: some View {
        VStack(alignment:.leading,spacing:16) {
            Text("S J Story   /   주차 메모").font(.system(size:15,weight:.semibold)).foregroundStyle(mint)
            Text("내 차, 어디에 세웠지?").font(.system(size:32,weight:.bold))
            Text("주차 직전 사진의 표지판을 읽어 지하·지상 주차장의 층, 구역, 위치 표지를 기록합니다.").foregroundStyle(.secondary)
            HStack(alignment:.top,spacing:20) {
                VStack(alignment:.leading,spacing:12) {
                    HStack {
                        Text("차량 카메라 입력").font(.headline); Spacer()
                        Text(demo.sample < 0 ? "내 사진" : "합성 샘플").font(.caption)
                    }
                    ZStack {
                        Color.black
                        if let photo = demo.preview { Image(nsImage:photo).resizable().aspectRatio(contentMode:.fit) }
                    }.frame(height:220).clipShape(RoundedRectangle(cornerRadius:10))
                    HStack { Menu(demo.sample < 0 ? "샘플 선택" : Self.sampleNames[demo.sample]) {
                            ForEach(Self.sampleNames.indices, id: \.self) { n in Button(Self.sampleNames[n]) { demo.selectSample(n) } }
                        }; Spacer(); Button("사진 열기…") { demo.pick() } }
                    Divider()
                    HStack { Text("기어  " + demo.gear.rawValue).font(.headline); Spacer(); Button("주차 완료 · P단 모의 입력") { demo.park() }.buttonStyle(.borderedProminent).disabled(demo.gear == .park || demo.preview == nil) }
                    Text(demo.feedback).font(.callout)
                    if !demo.raw.isEmpty { Text("읽은 글자   " + demo.raw).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                    if demo.gear == .park && !demo.busy {
                        HStack { TextField("예: 지상 3층 / 야외 · D구역",text:$demo.floor); TextField("위치 표지 (예: C-17)",text:$demo.pillar); Button("확인·저장") { demo.save() }.disabled(!demo.ready) }
                    }
                    Spacer(minLength:0)
                }.padding(18).frame(maxWidth:.infinity,alignment:.leading).background(panel)
                VStack(alignment:.leading,spacing:17) {
                    Label("휴대폰 알림 미리보기",systemImage:"iphone").font(.headline)
                    Text("마이현대 연동 제안 · 모의 화면").font(.caption).foregroundStyle(mint)
                    Divider()
                    if let note = demo.receipt {
                        Text("최근 주차 위치").font(.callout)
                        Text(note.floor).font(.system(size:34,weight:.bold))
                        if !note.pillar.isEmpty { Text(note.pillar + " 표지 옆").font(.title2).bold() }
                        Text(note.time.formatted(date:.abbreviated,time:.shortened)).font(.caption).foregroundStyle(.secondary)
                        Button("기록 지우기") { demo.clearReceipt() }
                    } else {
                        Text("아직 주차 알림이 없습니다.").font(.headline)
                        Text("주차 위치가 확인되면 이곳에 표시됩니다.").font(.callout).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text("이 Mac에는 최근 위치 한 건만 저장합니다. 사진은 서버로 보내지 않습니다.").font(.caption).foregroundStyle(.secondary)
                }.padding(18).frame(width:265).frame(maxHeight:.infinity,alignment:.topLeading).background(panel)
            }
        }.padding(24).frame(minWidth:850,minHeight:595).preferredColorScheme(.dark)
    }
    private var panel: some View { RoundedRectangle(cornerRadius:14).fill(Color.white.opacity(0.055)) }
}
@main
struct ParkingDemoApp: App {
    init() {
        let args = CommandLine.arguments
        if args.count == 3 && args[1] == "--read" {
            do {
                let r = try ParkingReader.read(URL(fileURLWithPath:args[2]))
                print(r.location, r.pillar, r.canNotify ? "AUTO" : "REVIEW", r.lines, r.scores, separator:" | "); exit(0)
            } catch { fputs("OCR error: \(error)\n", stderr); exit(1) }
        }
        NSApplication.shared.setActivationPolicy(.regular)
        NSApplication.shared.activate(ignoringOtherApps:true)
    }
    var body: some Scene { WindowGroup("S J Story · 주차 메모") { DemoView() }.defaultSize(width:920,height:620) }
}
