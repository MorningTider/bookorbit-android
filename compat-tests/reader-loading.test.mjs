import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
const source = await readFile(
  new URL("../app/src/main/assets/reader/reader-loading.js", import.meta.url),
  "utf8",
);
const { settleReaderFonts } = await import(
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
  assert.deepEqual(events, ["22px __serverfont_serif"]);
  release([]);
  await work;
  assert.deepEqual(events, ["22px __serverfont_serif", "frame", "frame"]);
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
