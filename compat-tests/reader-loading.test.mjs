import test from "node:test";
import assert from "node:assert/strict";
import {
  settleReaderFonts,
  prepareSectionFonts,
  createSectionFontLoader,
} from "../app/src/main/assets/reader/reader-loading.js";

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
  assert.deepEqual(events, [
    "400 22px __serverfont_serif",
    "700 22px __serverfont_serif",
  ]);
  release([]);
  await work;
  assert.deepEqual(events, [
    "400 22px __serverfont_serif",
    "700 22px __serverfont_serif",
    "frame",
    "frame",
  ]);
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
    "400 24px __serverfont_serif",
    "700 24px __serverfont_serif",
    "400 24px __serverfont_serif",
    "700 24px __serverfont_serif",
  ]);
  releases[0]([]);
  releases[1]([]);
  await first;
  assert.equal(visible, 1);
  releases[2]([]);
  releases[3]([]);
  await second;
  assert.equal(visible, 2);
});

test("chapter reveal waits for a separately loaded bold heading face", async () => {
  let releaseBody, releaseHeading;
  const body = new Promise((resolve) => {
    releaseBody = resolve;
  });
  const heading = new Promise((resolve) => {
    releaseHeading = resolve;
  });
  const requested = [];
  let shown = false;
  const doc = {
    fonts: {
      load: (value) => {
        requested.push(value);
        return value.startsWith("700 ") ? heading : body;
      },
      ready: Promise.resolve(),
    },
  };
  const work = prepareSectionFonts(
    doc,
    { fontFamily: "__serverfont_serif", fontSize: 22 },
    [
      { family: "__serverfont_serif", weight: 400 },
      { family: "__serverfont_serif", weight: 700 },
    ],
  ).then(() => {
    shown = true;
  });
  releaseBody([]);
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(
    shown,
    false,
    "Body readiness alone cannot reveal a chapter with an unloaded heading",
  );
  assert.ok(requested.includes("700 22px __serverfont_serif"));
  releaseHeading([]);
  await work;
  assert.equal(shown, true);
});

test("chapters and page counting reuse the identical decoded faces", async () => {
  let loads = 0;
  const created = [];
  class Font {
    constructor(family, source, descriptors) {
      Object.assign(this, { family, source, descriptors });
      created.push(this);
    }
    async load() {
      loads++;
      return this;
    }
  }
  const faces = [
    {
      family: "__serverfont_serif",
      fileName: "server-1-4321-aabbccddeeff.otf",
      format: "otf",
      weight: 400,
    },
    {
      family: "__serverfont_serif",
      fileName: "server-2-4321-aabbccddeeff.otf",
      format: "otf",
      weight: 700,
    },
  ];
  const loader = createSectionFontLoader(faces, Font);
  const documents = Array.from({ length: 8 }, () => ({ fonts: new Set() }));
  await Promise.all(documents.map(loader));
  assert.equal(loads, 2);
  for (const doc of documents) assert.deepEqual([...doc.fonts], created);
  assert.match(created[1].descriptors.weight, /700/);
});

test("a failed font load is retryable and cannot reveal fallback text", async () => {
  let loads = 0;
  class Font {
    async load() {
      if (++loads === 1) throw Error("interrupted font");
      return this;
    }
  }
  const loader = createSectionFontLoader(
    [
      {
        family: "__serverfont_serif",
        fileName: "server-1-4321-aabbccddeeff.otf",
        format: "otf",
        weight: 400,
      },
    ],
    Font,
  );
  const doc = { fonts: new Set() };
  await assert.rejects(loader(doc), /interrupted/);
  assert.equal(doc.fonts.size, 0);
  await loader(doc);
  assert.equal(loads, 2);
  assert.equal(doc.fonts.size, 1);
});

test("changing fonts releases previous reader faces while preserving embedded faces", async () => {
  class Font {
    constructor(family) {
      this.family = family;
    }
    async load() {
      return this;
    }
  }
  const embedded = { family: "Book heading" };
  const doc = { fonts: new Set([embedded]) };
  const face = {
    family: "__serverfont_serif",
    fileName: "server-1-4321-aabbccddeeff.otf",
    format: "otf",
    weight: 400,
  };
  await createSectionFontLoader([face], Font)(doc);
  assert.equal(doc.fonts.size, 2);
  await createSectionFontLoader(
    [{ ...face, family: "__serverfont_sans" }],
    Font,
  )(doc);
  assert.deepEqual(
    [...doc.fonts].map((f) => f.family),
    ["Book heading", "__serverfont_sans"],
  );
  await createSectionFontLoader([], Font)(doc);
  assert.deepEqual([...doc.fonts], [embedded]);
});

test("an earlier font selection finishing late cannot replace the current faces", async () => {
  let release;
  class Font {
    load() {
      return new Promise((resolve) => {
        release = () => resolve(this);
      });
    }
  }
  const face = {
    family: "__serverfont_serif",
    fileName: "server-1-4321-aabbccddeeff.otf",
    format: "otf",
    weight: 400,
  };
  let current = true;
  const loader = createSectionFontLoader([face], Font, () => current);
  const latest = { family: "Latest selection" };
  const doc = { fonts: new Set([latest]) };
  const work = loader(doc);
  current = false;
  release();
  await work;
  assert.deepEqual([...doc.fonts], [latest]);
});
