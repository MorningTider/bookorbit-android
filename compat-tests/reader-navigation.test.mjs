import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
const source = await readFile(
  new URL(
    "../app/src/main/assets/reader/reader-navigation.js",
    import.meta.url,
  ),
  "utf8",
);
const { createAutoReader, applyPageAnimation, applySidePadding } = await import(
  `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`
);

function fixture(scrolled = false) {
  let job = null;
  let stopped = 0;
  let ended = false;
  let clock = 0;
  const pages = [];
  const auto = createAutoReader({
    next: async (distance) => {
      pages.push(distance);
    },
    isScrolled: () => scrolled,
    isAtEnd: () => ended,
    viewportHeight: () => 800,
    onStopped: () => {
      stopped++;
    },
    clock: () => clock,
    timers: {
      setTimeout: (fn, delay) => {
        job = { fn, delay };
        return 1;
      },
      clearTimeout: () => {
        job = null;
      },
    },
  });
  return {
    auto,
    pages,
    stopCount: () => stopped,
    job: () => job,
    tick: async (elapsed) => {
      clock += elapsed;
      const task = job;
      job = null;
      if (task) await task.fn();
    },
    end: () => {
      ended = true;
    },
  };
}

test("page animation is offered only for supported paginated mode", () => {
  const attrs = new Set();
  const renderer = {
    setAttribute: (key) => attrs.add(key),
    removeAttribute: (key) => attrs.delete(key),
  };
  applyPageAnimation(renderer, { flow: "paginated", pageAnimation: true });
  assert.ok(attrs.has("animated"));
  applyPageAnimation(renderer, { flow: "scrolled", pageAnimation: true });
  assert.equal(attrs.has("animated"), false);
  applyPageAnimation(renderer, { flow: "paginated", pageAnimation: false });
  assert.equal(attrs.has("animated"), false);
});
test("automatic reading waits for the configured interval and stops cleanly", async () => {
  const f = fixture();
  f.auto.start(12);
  assert.equal(f.job().delay, 12000);
  await f.tick(12000);
  assert.equal(f.pages.length, 1);
  assert.equal(f.pages[0], undefined);
  f.auto.stop();
  assert.equal(f.job(), null);
});
test("scrolling advances according to elapsed time without overlapping requests", async () => {
  const f = fixture(true);
  f.auto.start(10);
  assert.equal(f.job().delay, 100);
  await f.tick(100);
  assert.equal(f.pages[0], 8);
  await f.tick(200);
  assert.equal(f.pages[1], 16);
});
test("end of book stops instead of scheduling more turns", async () => {
  const f = fixture();
  f.auto.start(10);
  f.end();
  await f.tick(10000);
  assert.equal(f.pages.length, 0);
  assert.equal(f.stopCount(), 1);
  assert.equal(f.job(), null);
});
test("stale scheduled callbacks cannot restart a stopped reader", async () => {
  const f = fixture();
  f.auto.start(10);
  const stale = f.job();
  f.auto.stop();
  await stale.fn();
  assert.equal(f.pages.length, 0);
  assert.equal(f.job(), null);
});
test("intervals are bounded to prevent a busy loop", () => {
  const f = fixture();
  f.auto.start(-1);
  assert.equal(f.job().delay, 5000);
  f.auto.start(9999);
  assert.equal(f.job().delay, 120000);
});

test("side padding uses Foliate percentages and recalculates on rotation", () => {
  const attributes = new Map();
  const renderer = {
    getAttribute: (key) => attributes.get(key),
    setAttribute: (key, value) => attributes.set(key, value),
  };
  applySidePadding(renderer, 20, 400);
  assert.equal(attributes.get("gap"), "5%");
  applySidePadding(renderer, 20, 800);
  assert.equal(attributes.get("gap"), "2.5%");
  applySidePadding(renderer, 1000, 320);
  assert.equal(attributes.get("gap"), "40%");
  applySidePadding(renderer, -10, 320);
  assert.equal(attributes.get("gap"), "0%");
});

test("native paper curl disables the Foliate slide animation", () => {
  const values = new Set(["animated"]);
  const renderer = {
    setAttribute: (key) => values.add(key),
    removeAttribute: (key) => values.delete(key),
  };
  applyPageAnimation(renderer, {
    flow: "paginated",
    pageAnimation: true,
    pageCurl: true,
  });
  assert.equal(values.has("animated"), false);
});
