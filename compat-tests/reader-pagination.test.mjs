import test from "node:test";
import assert from "node:assert/strict";
import { pagePosition, createPageCounter } from "../app/src/main/assets/reader/reader-pagination.js";

test("whole-book page numbers wait until all linear sections have been laid out", () => {
  assert.equal(pagePosition([4, null, 6], 0, 1), null);
  assert.deepEqual(pagePosition([4, 2, 6], 2, 3), { current: 9, total: 12 });
});

test("nonlinear sections do not add pages and the final screen is bounded", () => {
  assert.deepEqual(pagePosition([0, 4, 3, 0], 2, 3), { current: 7, total: 7 });
  assert.deepEqual(pagePosition([0, 4, 3, 0], 2, 4), { current: 7, total: 7 });
});


test("whole-book measurement waits for interaction to settle and resumes automatically", async () => {
  const previous = globalThis.document;
  let busy = true;
  let measurements = 0;
  let removed = false;
  const measuring = {
    style: {}, setAttribute() {}, open() {}, setStyles() {}, destroy() {},
    remove() { removed = true; }, pages: 6,
    async goTo() { measurements++; },
  };
  globalThis.document = { hidden: false, createElement: () => measuring, body: { append() {} } };
  const counter = createPageCounter({
    book: { sections: [{}] },
    renderer: { attributes: [], getBoundingClientRect: () => ({ width: 400, height: 800 }) },
    styles: () => "", prepare() {}, onChange() {}, isBusy: () => busy,
  });
  try {
    await new Promise(resolve => setTimeout(resolve, 300));
    assert.equal(measurements, 0);
    busy = false;
    const deadline = Date.now() + 2000;
    while (!removed && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(measurements, 1);
    assert.deepEqual(counter.counts, [4]);
    assert.equal(removed, true);
  } finally {
    counter.destroy();
    globalThis.document = previous;
  }
});
