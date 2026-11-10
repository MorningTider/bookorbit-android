import test from "node:test";
import assert from "node:assert/strict";
import { pagePosition } from "../app/src/main/assets/reader/reader-pagination.js";

test("whole-book page numbers wait until all linear sections have been laid out", () => {
  assert.equal(pagePosition([4, null, 6], 0, 1), null);
  assert.deepEqual(pagePosition([4, 2, 6], 2, 3), { current: 9, total: 12 });
});

test("nonlinear sections do not add pages and the final screen is bounded", () => {
  assert.deepEqual(pagePosition([0, 4, 3, 0], 2, 3), { current: 7, total: 7 });
  assert.deepEqual(pagePosition([0, 4, 3, 0], 2, 4), { current: 7, total: 7 });
});
