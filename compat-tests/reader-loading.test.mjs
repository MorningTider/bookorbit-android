import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
const source = await readFile(
  new URL("../app/src/main/assets/reader/reader-loading.js", import.meta.url),
  "utf8",
);
const { settleReaderFonts, prepareSectionFonts } = await import(
  `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`
);

test("the first page waits for the selected font and two layout frames", async () => {
  let release;
  const waiting = new Promise((resolve) => {
    release = resolve;
  });
  const events = [];
  const doc = {
    fonts: {
      load: (value) => {
        events.push(value);
        return waiting;
      },
      ready: Promise.resolve(),
    },
  };
  const view = { renderer: { getContents: () => [{ doc }] } };
  let presented = false;
  const work = settleReaderFonts(
    view,
    { fontFamily: "__serverfont_serif", fontSize: 22 },
    [{ family: "__serverfont_serif" }],
    async () => {
      events.push("frame");
    },
  ).then(() => {
    presented = true;
  });
  await Promise.resolve();
  assert.equal(presented, false);
  assert.deepEqual(events, ["400 22px __serverfont_serif"]);
  release([]);
  await work;
  assert.deepEqual(events, ["400 22px __serverfont_serif", "frame", "frame"]);
  assert.equal(presented, true);
});

test("built-in fonts do not request unavailable private faces", async () => {
  let loads = 0;
  const view = {
    renderer: {
      getContents: () => [
        {
          doc: {
            fonts: {
              load: () => {
                loads++;
              },
              ready: Promise.resolve(),
            },
          },
        },
      ],
    },
  };
  await settleReaderFonts(view, { fontFamily: "serif" }, [], async () => {});
  assert.equal(loads, 0);
});

test("every new chapter waits for its own selected weight before becoming visible", async () => {
  const releases = [];
  const seen = [];
  let visible = 0;
  const chapter = () => ({
    fonts: {
      load: (value) => {
        seen.push(value);
        return new Promise((resolve) => releases.push(resolve));
      },
      ready: Promise.resolve(),
    },
  });
  const settings = {
    fontFamily: "__serverfont_serif",
    fontSize: 24,
    fontWeight: 700,
  };
  const faces = [{ family: "__serverfont_serif" }];
  const first = prepareSectionFonts(chapter(), settings, faces).then(
    () => visible++,
  );
  const second = prepareSectionFonts(chapter(), settings, faces).then(
    () => visible++,
  );
  await Promise.resolve();
  assert.equal(visible, 0);
  assert.deepEqual(seen, [
    "700 24px __serverfont_serif",
    "700 24px __serverfont_serif",
  ]);
  releases[0]([]);
  await first;
  assert.equal(visible, 1);
  releases[1]([]);
  await second;
  assert.equal(visible, 2);
});
