import test from "node:test";
import assert from "node:assert/strict";
import { createReaderIdleGate } from "../app/src/main/assets/reader/reader-idle.js";

test("pagination yields throughout paper gestures and the settling interval", () => {
  let time = 0;
  const gate = createReaderIdleGate(() => time);
  assert.equal(gate.isBusy(), false);
  gate.setActive(true, "pointer");
  time = 2000;
  assert.equal(gate.isBusy(), true);
  gate.setActive(true);
  gate.setActive(false, "pointer");
  time += 2000;
  assert.equal(gate.isBusy(), true);
  gate.setActive(false);
  time += 749;
  assert.equal(gate.isBusy(), true);
  time++;
  assert.equal(gate.isBusy(), false);
});

test("new input postpones background measurement", () => {
  let time = 0;
  const gate = createReaderIdleGate(() => time);
  gate.touch();
  time = 700;
  gate.touch();
  time = 1000;
  assert.equal(gate.isBusy(), true);
  time = 1450;
  assert.equal(gate.isBusy(), false);
});
