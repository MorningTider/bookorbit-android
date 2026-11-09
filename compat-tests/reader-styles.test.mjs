import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
const source = await readFile(
  new URL("../app/src/main/assets/reader/reader-styles.js", import.meta.url),
  "utf8",
);
const { fontFaceCSS, typographyCSS, paperBackground } = await import(
  `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`
);

const face = {
  family: "__serverfont_noto_serif",
  fileName: "server-1-4321-aabbccddeeff.otf",
  format: "otf",
  weight: 400,
  weightMin: null,
  weightMax: null,
  style: "normal",
};
test("custom fonts use the private local font handler", () => {
  const css = fontFaceCSS([face]);
  assert.match(css, /appassets.androidplatform.net\/reader-fonts\/server-1/);
  assert.match(css, /font-weight: 400 400/);
  assert.doesNotMatch(css, /Bearer|token/);
});
test("rejects paths, family names and formats that could inject CSS", () => {
  for (const changed of [
    { fileName: "../secret.otf" },
    { family: 'x"; color:red' },
    { format: "svg" },
    { fileName: "server-1-4321-aabbccddeeff.otf?token=secret" },
  ]) {
    assert.equal(fontFaceCSS([{ ...face, ...changed }]), "");
  }
});
test("variable weights and italic faces survive the bridge", () => {
  assert.match(
    fontFaceCSS([{ ...face, weightMin: 100, weightMax: 900, style: "italic" }]),
    /font-weight: 100 900; font-style: italic/,
  );
});
test("paragraph and character spacing are bounded and indentation is optional", () => {
  const settings = {
    fontSize: 22,
    lineHeight: 1.8,
    fontWeight: 700,
    paragraphSpacing: 99,
    letterSpacing: 99,
    paragraphIndent: true,
  };
  const css = typographyCSS(settings);
  assert.match(css, /font-size: 22px/);
  assert.match(css, /line-height: 1.8 !important/);
  assert.match(css, /font-weight: 700/);
  assert.match(css, /letter-spacing: 0.3em/);
  assert.match(css, /margin-block: 2em/);
  assert.match(css, /text-indent: 2em/);
  assert.doesNotMatch(
    typographyCSS({ ...settings, paragraphIndent: false }),
    /text-indent/,
  );
});

test("paper backgrounds provide bounded CSS presets and unknown values stay plain", () => {
  assert.match(
    paperBackground("paper", "#fff").image,
    /repeating-linear-gradient/,
  );
  assert.match(paperBackground("soft", "#fff").image, /radial-gradient/);
  assert.deepEqual(paperBackground("plain", "#fff"), {
    color: "#fff",
    image: "none",
  });
  assert.equal(paperBackground("url(private)", "#fff").image, "none");
});
