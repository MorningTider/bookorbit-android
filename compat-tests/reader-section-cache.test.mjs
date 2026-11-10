import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
const source = await readFile(
  new URL(
    "../app/src/main/assets/reader/reader-section-cache.js",
    import.meta.url,
  ),
  "utf8",
);
const { createSectionCache } = await import(
  `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`
);
const tick = () => new Promise((resolve) => setImmediate(resolve));
function fixture(count = 5) {
  const loads = Array(count).fill(0),
    releases = Array(count).fill(0);
  const sections = loads.map((_, index) => ({
    load: async () => {
      loads[index]++;
      return `section-${index}`;
    },
    unload: () => releases[index]++,
  }));
  return { loads, releases, sections, cache: createSectionCache(sections) };
}

test("turning into a warmed section consumes its resource without loading twice", async () => {
  const f = fixture();
  f.cache.warm([1, 3]);
  await tick();
  assert.equal(await f.cache.take(1), "section-1");
  assert.equal(f.loads[1], 1);
  assert.equal(f.releases[1], 0);
  f.cache.destroy();
  await tick();
  assert.equal(f.releases[1], 0, "Visible reader now owns section 1");
  assert.equal(f.releases[3], 1);
});

test("only two adjacent resources are retained and eviction releases the old owner", async () => {
  const f = fixture();
  f.cache.warm([0, 1, 4]);
  await tick();
  assert.deepEqual(f.loads, [1, 1, 0, 0, 0]);
  f.cache.warm([1, 2]);
  await tick();
  assert.deepEqual(f.loads, [1, 1, 1, 0, 0]);
  assert.equal(f.releases[0], 1);
  f.cache.destroy();
  await tick();
  assert.deepEqual(f.releases, [1, 1, 1, 0, 0]);
});

test("evicting an in-flight preload releases it after completion", async () => {
  const f = fixture();
  let complete;
  f.sections[1].load = () =>
    new Promise((resolve) => {
      complete = resolve;
    });
  f.cache.warm([1]);
  await tick();
  f.cache.warm([2]);
  assert.equal(f.releases[1], 0);
  complete("section-1");
  await tick();
  assert.equal(f.releases[1], 1);
  f.cache.destroy();
});

test("a failed speculative load is retried by real navigation", async () => {
  const f = fixture();
  let calls = 0;
  f.sections[1].load = async () => {
    if (++calls === 1) throw new Error("decode failed");
    return "retry";
  };
  f.cache.warm([1]);
  await tick();
  assert.equal(await f.cache.take(1), "retry");
  assert.equal(calls, 2);
  f.cache.destroy();
});

test("a pending preload can be claimed by navigation and is not released by destroy", async () => {
  const f = fixture();
  let complete;
  f.sections[1].load = () =>
    new Promise((resolve) => {
      complete = resolve;
    });
  f.cache.warm([1]);
  await tick();
  const navigation = f.cache.take(1);
  f.cache.destroy();
  complete("section-1");
  assert.equal(await navigation, "section-1");
  await tick();
  assert.equal(f.releases[1], 0);
  await assert.rejects(f.cache.take(2), /closed/);
});
