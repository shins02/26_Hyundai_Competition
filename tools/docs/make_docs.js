// 마크다운 문서를 로고 워터마크가 들어간 Word(.docx)와 인쇄용 HTML로 바꿉니다.
// 사용법: NODE_PATH=<docx가 설치된 node_modules> node make_docs.js <문서.md> <워터마크.png> <머리말 문구>
// 결과: 같은 폴더에 <문서>.docx 와 <문서>.print.html (Chrome으로 PDF 인쇄용, 인쇄 뒤 지워도 됨)
const fs = require("fs");
const path = require("path");
const d = require("docx");

const [mdPath, wmPath, headerText] = process.argv.slice(2);
const md = fs.readFileSync(mdPath, "utf8").replace(/\r/g, "");
const wm = fs.readFileSync(wmPath);
const FONT = { ascii: "Malgun Gothic", hAnsi: "Malgun Gothic", eastAsia: "맑은 고딕", cs: "Malgun Gothic" };
const MONO = { ascii: "Consolas", hAnsi: "Consolas", eastAsia: "맑은 고딕", cs: "Consolas" };
const NAVY = "04456F";
const PAGE_W = 11906, MARGIN = 1134, CONTENT_W = PAGE_W - MARGIN * 2; // A4, 여백 2cm

// ---------- 블록 나누기 ----------
const lines = md.split("\n");
const blocks = [];
for (let i = 0; i < lines.length; i++) {
  const l = lines[i];
  if (/^```/.test(l)) {
    const code = []; i++;
    while (i < lines.length && !/^```/.test(lines[i])) code.push(lines[i++]);
    blocks.push({ t: "code", lines: code }); continue;
  }
  if (/^\s*$/.test(l)) continue;
  let m;
  if ((m = l.match(/^(#{1,6})\s+(.*)$/))) { blocks.push({ t: "h", level: m[1].length, text: m[2] }); continue; }
  if (/^(---+|\*\*\*+)\s*$/.test(l)) { blocks.push({ t: "hr" }); continue; }
  if (/^\|/.test(l)) {
    const rows = [];
    while (i < lines.length && /^\|/.test(lines[i])) rows.push(lines[i++]);
    i--;
    const cells = r => r.trim().replace(/^\||\|$/g, "").split(/(?<!\\)\|/).map(c => c.trim().replace(/\\\|/g, "|"));
    const body = rows.filter(r => !/^\|\s*:?-{2,}/.test(r)).map(cells);
    blocks.push({ t: "table", rows: body }); continue;
  }
  if (/^>\s?/.test(l)) {
    const q = [];
    while (i < lines.length && /^>\s?/.test(lines[i])) q.push(lines[i++].replace(/^>\s?/, ""));
    i--;
    blocks.push({ t: "quote", text: q.join(" ") }); continue;
  }
  if ((m = l.match(/^(\s*)([-*]|\d+(?:-\d+)?\.)\s+(.*)$/))) {
    const items = [];
    while (i < lines.length && (m = lines[i].match(/^(\s*)([-*]|\d+(?:-\d+)?\.)\s+(.*)$/))) {
      let text = m[3];
      while (i + 1 < lines.length && /^\s{2,}\S/.test(lines[i + 1]) && !/^(\s*)([-*]|\d+(?:-\d+)?\.)\s+/.test(lines[i + 1])) text += " " + lines[++i].trim();
      items.push({ depth: Math.floor(m[1].length / 2), marker: /^\d/.test(m[2]) ? m[2] : null, text });
      i++;
    }
    i--;
    blocks.push({ t: "list", items }); continue;
  }
  const para = [l];
  while (i + 1 < lines.length && lines[i + 1].trim() && !/^(#{1,6}\s|```|\||>|\s*([-*]|\d+(?:-\d+)?\.)\s|---)/.test(lines[i + 1])) para.push(lines[++i]);
  blocks.push({ t: "p", text: para.join(" ") });
}

// ---------- 글자 꾸밈: **굵게**, `코드`, [링크](주소), *기울임* ----------
function tokens(text) {
  const out = [];
  const re = /(\*\*([^*]+)\*\*|`([^`]+)`|\[([^\]]+)\]\(([^)]+)\)|(?<![*\w])\*([^*\s][^*]*)\*(?!\*)|<br>)/g;
  let last = 0, m;
  while ((m = re.exec(text))) {
    if (m.index > last) out.push({ text: text.slice(last, m.index) });
    if (m[2]) out.push({ text: m[2], bold: true });
    else if (m[3]) out.push({ text: m[3], code: true });
    else if (m[4]) out.push({ text: m[4], link: m[5] });
    else if (m[6]) out.push({ text: m[6], italic: true });
    else out.push({ br: true });
    last = re.lastIndex;
  }
  if (last < text.length) out.push({ text: text.slice(last) });
  return out;
}
const runs = (text, base = {}) => tokens(text).map(t => {
  if (t.br) return new d.TextRun({ break: 1 });
  const o = { text: t.text, font: t.code ? MONO : FONT, size: base.size || 20, bold: t.bold || base.bold, italics: t.italic, color: base.color };
  if (t.code) { o.shading = { type: d.ShadingType.CLEAR, fill: "EEF2F6", color: "auto" }; o.size = (base.size || 20) - 2; }
  if (t.link && /^https?:/.test(t.link)) return new d.ExternalHyperlink({ link: t.link, children: [new d.TextRun({ ...o, color: "1F5FBF", underline: {} })] });
  return new d.TextRun(o);
});

// ---------- Word ----------
const HEAD = { 1: { size: 36, color: NAVY }, 2: { size: 28, color: NAVY }, 3: { size: 24, color: "1C2B3A" }, 4: { size: 22, color: "1C2B3A" } };
const children = [];
const border = { style: d.BorderStyle.SINGLE, size: 4, color: "C9D3DE" };
for (const b of blocks) {
  if (b.t === "h") {
    const lvl = Math.min(b.level, 4);
    children.push(new d.Paragraph({ heading: [d.HeadingLevel.TITLE, d.HeadingLevel.HEADING_1, d.HeadingLevel.HEADING_2, d.HeadingLevel.HEADING_3][lvl - 1],
      children: runs(b.text, { size: HEAD[lvl].size, bold: true, color: HEAD[lvl].color }),
      border: lvl === 2 ? { bottom: { style: d.BorderStyle.SINGLE, size: 6, color: "9FB3C8", space: 2 } } : undefined }));
  } else if (b.t === "p") {
    children.push(new d.Paragraph({ children: runs(b.text), spacing: { after: 120, line: 300 } }));
  } else if (b.t === "quote") {
    children.push(new d.Paragraph({ children: runs(b.text, { color: "374151" }), spacing: { before: 60, after: 160, line: 300 }, indent: { left: 240 },
      border: { left: { style: d.BorderStyle.SINGLE, size: 18, color: "6C93BA", space: 8 } }, shading: { type: d.ShadingType.CLEAR, fill: "F3F6F9", color: "auto" } }));
  } else if (b.t === "hr") {
    children.push(new d.Paragraph({ children: [], border: { bottom: { style: d.BorderStyle.SINGLE, size: 6, color: "C9D3DE", space: 1 } }, spacing: { after: 120 } }));
  } else if (b.t === "code") {
    b.lines.forEach((l, k) => children.push(new d.Paragraph({ children: [new d.TextRun({ text: l || " ", font: MONO, size: 15 })],
      shading: { type: d.ShadingType.CLEAR, fill: "F3F5F8", color: "auto" }, spacing: { before: k ? 0 : 80, after: k === b.lines.length - 1 ? 160 : 0, line: 240 } })));
  } else if (b.t === "list") {
    for (const it of b.items) {
      const opt = { children: it.marker ? [new d.TextRun({ text: it.marker + " ", font: FONT, size: 20, bold: true }), ...runs(it.text)] : runs(it.text), spacing: { after: 60, line: 290 } };
      if (it.marker) opt.indent = { left: 360 + it.depth * 360, hanging: 300 };
      else opt.numbering = { reference: "bullets", level: Math.min(it.depth, 2) };
      children.push(new d.Paragraph(opt));
    }
    children.push(new d.Paragraph({ children: [], spacing: { after: 60 } }));
  } else if (b.t === "table") {
    const cols = Math.max(...b.rows.map(r => r.length));
    const len = Array.from({ length: cols }, (_, c) => Math.max(...b.rows.map(r => Math.min(60, (r[c] || "").length)), (b.rows[0][c] || "").length + 3, 9)); // 제목 글자가 꺾이지 않게
    const total = len.reduce((a, x) => a + x, 0);
    const widths = len.map(x => Math.floor(CONTENT_W * x / total));
    widths[cols - 1] += CONTENT_W - widths.reduce((a, x) => a + x, 0);
    children.push(new d.Table({ width: { size: CONTENT_W, type: d.WidthType.DXA }, columnWidths: widths,
      rows: b.rows.map((r, ri) => new d.TableRow({ tableHeader: ri === 0, children: Array.from({ length: cols }, (_, c) => new d.TableCell({
        width: { size: widths[c], type: d.WidthType.DXA }, borders: { top: border, bottom: border, left: border, right: border },
        shading: ri === 0 ? { type: d.ShadingType.CLEAR, fill: "E6EDF4", color: "auto" } : undefined,
        margins: { top: 50, bottom: 50, left: 90, right: 90 },
        children: [new d.Paragraph({ children: runs(r[c] || "", { size: 17, bold: ri === 0 }), spacing: { line: 260 } })] })) })) }));
    children.push(new d.Paragraph({ children: [], spacing: { after: 120 } }));
  }
}

const watermark = new d.Paragraph({ children: [new d.ImageRun({ type: "png", data: wm, transformation: { width: 360, height: 360 },
  // 쪽 가운데: 그림 360px = 95.25mm, A4 210 × 297mm. 1mm = 36000 EMU
  floating: { horizontalPosition: { relative: d.HorizontalPositionRelativeFrom.PAGE, offset: Math.round((210 - 95.25) / 2 * 36000) },
    verticalPosition: { relative: d.VerticalPositionRelativeFrom.PAGE, offset: Math.round((297 - 95.25) / 2 * 36000) },
    behindDocument: true, allowOverlap: true, wrap: { type: d.TextWrappingType.NONE } },
  altText: { name: "SJ 로고 워터마크", description: "SJ 로고 워터마크", title: "SJ 로고" } })] });
const header = new d.Header({ children: [watermark, new d.Paragraph({ alignment: d.AlignmentType.RIGHT,
  children: [new d.TextRun({ text: headerText, font: FONT, size: 16, color: "7B8794" })] })] });
const footer = new d.Footer({ children: [new d.Paragraph({ alignment: d.AlignmentType.CENTER,
  children: [new d.TextRun({ children: [d.PageNumber.CURRENT, " / ", d.PageNumber.TOTAL_PAGES], font: FONT, size: 16, color: "7B8794" })] })] });
const headingStyle = (id, name, lvl, size, color) => ({ id, name, basedOn: "Normal", next: "Normal", quickFormat: true,
  run: { font: FONT, size, bold: true, color }, paragraph: { spacing: { before: lvl === 0 ? 0 : 280, after: 120 }, keepNext: true, outlineLevel: lvl } });
const doc = new d.Document({
  creator: "S J Story", title: path.basename(mdPath, ".md"),
  styles: { default: { document: { run: { font: FONT, size: 20 } } },
    paragraphStyles: [headingStyle("Title", "Title", 0, 36, NAVY), headingStyle("Heading1", "Heading 1", 1, 28, NAVY),
      headingStyle("Heading2", "Heading 2", 2, 24, "1C2B3A"), headingStyle("Heading3", "Heading 3", 3, 22, "1C2B3A")] },
  numbering: { config: [{ reference: "bullets", levels: [0, 1, 2].map(level => ({ level, format: d.LevelFormat.BULLET, text: ["•", "◦", "▪"][level],
    alignment: d.AlignmentType.LEFT, style: { paragraph: { indent: { left: 360 + level * 360, hanging: 240 } } } })) }] },
  sections: [{ properties: { page: { size: { width: PAGE_W, height: 16838 }, margin: { top: 1134, bottom: 1134, left: MARGIN, right: MARGIN, header: 567, footer: 567 } } },
    headers: { default: header }, footers: { default: footer }, children }],
});
const out = mdPath.replace(/\.md$/, ".docx");
d.Packer.toBuffer(doc).then(buf => { fs.writeFileSync(out, buf); console.log("Word:", out); });

// ---------- 인쇄용 HTML (PDF) ----------
const esc = s => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const inline = text => tokens(text).map(t => t.br ? "<br>" : t.code ? `<code>${esc(t.text)}</code>` : t.bold ? `<b>${esc(t.text)}</b>` :
  t.italic ? `<i>${esc(t.text)}</i>` : t.link && /^https?:/.test(t.link) ? `<a href="${esc(t.link)}">${esc(t.text)}</a>` : esc(t.text)).join("");
let html = "";
for (const b of blocks) {
  if (b.t === "h") html += `<h${Math.min(b.level, 4)}>${inline(b.text)}</h${Math.min(b.level, 4)}>`;
  else if (b.t === "p") html += `<p>${inline(b.text)}</p>`;
  else if (b.t === "quote") html += `<blockquote>${inline(b.text)}</blockquote>`;
  else if (b.t === "hr") html += "<hr>";
  else if (b.t === "code") html += `<pre>${esc(b.lines.join("\n"))}</pre>`;
  else if (b.t === "list") html += `<div class="list">${b.items.map(it => `<div class="li" style="padding-left:${18 + it.depth * 18}px">${it.marker ? `<b>${it.marker}</b> ` : `<span class="mk">${["•", "◦", "▪"][Math.min(it.depth, 2)]}</span>`}${inline(it.text)}</div>`).join("")}</div>`;
  else if (b.t === "table") html += `<table>${b.rows.map((r, i) => `<tr>${r.map(c => i ? `<td>${inline(c)}</td>` : `<th>${inline(c)}</th>`).join("")}</tr>`).join("")}</table>`;
}
const wmData = "data:image/png;base64," + wm.toString("base64");
fs.writeFileSync(mdPath.replace(/\.md$/, ".print.html"), `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>${esc(path.basename(mdPath, ".md"))}</title><style>
@page { size: A4; margin: 18mm 18mm 16mm; }
body { font-family: "Apple SD Gothic Neo", "Malgun Gothic", sans-serif; font-size: 10pt; line-height: 1.6; color: #1c2430; }
.wm { position: fixed; left: 50%; top: 50%; width: 95mm; transform: translate(-50%, -50%); z-index: -1; }
.hd { position: fixed; top: -11mm; right: 0; font-size: 8pt; color: #7b8794; }
h1 { font-size: 18pt; color: #04456f; margin: 0 0 10pt; line-height: 1.35; }
h2 { font-size: 14pt; color: #04456f; border-bottom: 1px solid #9fb3c8; padding-bottom: 3pt; margin: 18pt 0 8pt; break-after: avoid; }
h3 { font-size: 12pt; margin: 14pt 0 6pt; break-after: avoid; } h4 { font-size: 11pt; margin: 12pt 0 6pt; }
p { margin: 0 0 6pt; } blockquote { margin: 4pt 0 10pt; padding: 6pt 10pt; border-left: 3pt solid #6c93ba; background: rgba(243,246,249,.85); color: #374151; }
code { font-family: Menlo, Consolas, monospace; font-size: 8.6pt; background: rgba(238,242,246,.9); padding: 0 2pt; border-radius: 2pt; }
pre { font-family: Menlo, "Apple SD Gothic Neo", monospace; font-size: 7.4pt; line-height: 1.35; background: rgba(243,245,248,.85); padding: 8pt; white-space: pre; overflow: hidden; break-inside: avoid; }
.list { margin: 0 0 8pt; } .li { position: relative; margin: 0 0 2pt; } .mk { position: absolute; margin-left: -14px; font-weight: 700; }
.li .mk:not(:empty) { min-width: 12px; } .li { text-indent: 0; }
table { width: 100%; border-collapse: collapse; margin: 4pt 0 10pt; font-size: 8.6pt; line-height: 1.45; }
th, td { border: 1px solid #c9d3de; padding: 3pt 5pt; vertical-align: top; text-align: left; } th { background: rgba(230,237,244,.9); }
tr { break-inside: avoid; } a { color: #1f5fbf; } hr { border: 0; border-top: 1px solid #c9d3de; }
</style></head><body><img class="wm" src="${wmData}" alt=""><div class="hd">${esc(headerText)}</div>${html}</body></html>`);
