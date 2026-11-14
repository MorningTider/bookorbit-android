import test from "node:test";
import assert from "node:assert/strict";
import { speechEnd, createReaderSpeech } from "../app/src/main/assets/reader/reader-speech.js";

test("speech chunks are bounded and preserve emoji surrogate pairs", () => {
  const text = "甲".repeat(799) + "😀" + "乙".repeat(900);
  let start = 0;
  const chunks = [];
  while (start < text.length) {
    const end = speechEnd(text, start);
    const part = text.slice(start, end);
    assert.ok(part.length <= 800);
    assert.ok(!/[\uD800-\uDBFF]$/.test(part));
    assert.ok(!/^[\uDC00-\uDFFF]/.test(part));
    chunks.push(part);
    start = end;
  }
  assert.equal(chunks.join(""), text);
});

test("speech chunks prefer sentence boundaries without dropping characters", () => {
  const text = "甲".repeat(600) + "。" + "乙".repeat(500);
  assert.equal(speechEnd(text, 0), 601);
});

test("an unavailable document is reported only to the requesting speech session", async () => {
  const events = [];
  const speech = createReaderSpeech(() => null, event => events.push(event));
  await speech.command({ action: "start", session: 7 });
  assert.deepEqual(events, [{ type: "speech", session: 7, error: true }]);
  speech.stop();
  await speech.command({ action: "next", session: 7 });
  assert.equal(events.length, 1);
});
