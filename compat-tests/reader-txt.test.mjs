import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
const source = await readFile(new URL("../app/src/main/assets/reader/reader-txt.js", import.meta.url), "utf8");
const { detectTextEncoding, readTextSections, textSectionDocument, makeTextBook } = await import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);

test("UTF-8 chapters reproduce the server's h1/p tree without whitespace nodes", async () => {
  const parsed = await readTextSections(new Blob(["第一章 开始\r\n第一段 <>&\r\n第二段\r\n第2章 继续\n后文"]), "文件名");
  assert.deepEqual(parsed.sections.map(s => s.title), ["第一章 开始", "第2章 继续"]);
  assert.deepEqual(parsed.sections[0].paragraphs, ["第一段 <>&", "第二段"]);
  assert.equal(textSectionDocument(parsed.sections[0]), '<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章 开始</title></head><body><h1>第一章 开始</h1><p>第一段 &lt;&gt;&amp;</p><p>第二段</p></body></html>');
});
test("GB18030 Chinese is recognized without a BOM", async () => {
  const bytes = new Uint8Array([0xb5,0xda,0xd2,0xbb,0xd5,0xc2,0x0a,0xb2,0xe2,0xca,0xd4]);
  assert.equal(detectTextEncoding(bytes), "gb18030");
  const parsed = await readTextSections(new Blob([bytes]));
  assert.equal(parsed.sections[0].title, "第一章");
  assert.deepEqual(parsed.sections[0].paragraphs, ["测试"]);
});
test("UTF-16 LE/BE with BOM preserve Chinese paragraphs", async () => {
  const little = Buffer.from("\ufeff第一章 测试\n正文", "utf16le");
  for (const bytes of [little, Buffer.from(little).swap16()]) {
    const parsed = await readTextSections(new Blob([bytes]));
    assert.equal(parsed.sections[0].title, "第一章 测试");
    assert.deepEqual(parsed.sections[0].paragraphs, ["正文"]);
  }
});
test("a long preface and continuation split exactly at the server's 100000 UTF-16 units", async () => {
  const parsed = await readTextSections(new Blob(["序文\n第一章 开始\n" + "中".repeat(100005)]), "文件名");
  assert.deepEqual(parsed.sections.map(s => [s.title, s.characters]), [["文件名",2],["第一章 开始",100000],["第一章 开始 (2)",5]]);
});
test("English and Chinese chapter headings follow the server rules", async () => {
  const parsed = await readTextSections(new Blob(["前言\n正文\nChapter 1: Start\nBody\n后记\n结束"]));
  assert.deepEqual(parsed.sections.map(s => s.title), ["前言","Chapter 1: Start","后记"]);
});
test("HTML, scripts and controls remain inert text", async () => {
  const parsed = await readTextSections(new Blob(['第1章\n<script>alert("x")</script>\u0001']));
  const html = textSectionDocument(parsed.sections[0]);
  assert.ok(!html.includes("<script>"));
  assert.ok(html.includes("&lt;script&gt;"));
  assert.ok(!html.includes("\u0001"));
});
test("empty, incomplete Unicode and oversized lines are rejected", async () => {
  await assert.rejects(readTextSections(new Blob([])));
  await assert.rejects(readTextSections(new Blob([new Uint8Array([0xff,0xfe,1])])));
  await assert.rejects(readTextSections(new Blob([new Uint8Array([0xef,0xbb,0xbf,0xff])])));
  await assert.rejects(readTextSections(new Blob(["A".repeat(1048577)])));
});
test("prepared book uses EPUB-compatible spine CFI bases and releases lazy URLs", async () => {
  const book = await makeTextBook(new Blob(["第1章\n正文\n第2章\n其他"]), {title:"文件名"});
  assert.equal(book.sections[0].cfi, "epubcfi(/6/2)");
  assert.equal(book.sections[1].cfi, "epubcfi(/6/4)");
  assert.deepEqual(book.splitTOCHref("1#p0"),["OEBPS/chapter-1.xhtml","p0"]);
  assert.equal(book.resolveHref("1").index,1);
  assert.equal(book.toc[1].href,"OEBPS/chapter-1.xhtml");
  assert.equal(book.resolveHref(book.toc[1].href).index,1);
  const url = book.sections[0].load();
  assert.equal(book.sections[0].load(),url);
  book.sections[0].unload();
  assert.notEqual(book.sections[0].load(),url);
  book.destroy();
});
