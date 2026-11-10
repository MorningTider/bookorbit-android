import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
const source = await readFile(
  new URL("../app/src/main/assets/reader/foliate/epub.js", import.meta.url),
  "utf8",
);
const loaderSource = source.slice(
  source.indexOf("class Loader {"),
  source.indexOf("const getHTMLFragment"),
);
// Run the shipped loader, with raw binary assets so the parser-independent ownership path is tested.
const Loader = new Function("MIME", `${loaderSource}; return Loader;`)({
  JS: /javascript/,
  XHTML: "xhtml",
  HTML: "html",
  CSS: "css",
  SVG: "svg",
});
const item = { href: "chapter.bin", mediaType: "application/octet-stream" };
function fixture() {
  let loads = 0;
  const loader = new Loader({
    loadBlob: async () => {
      loads++;
      return new Blob(["chapter"]);
    },
    resources: { manifest: [item] },
  });
  return { loader, loads: () => loads };
}

test("three sequential root owners keep the resource alive until their last release", async () => {
  const { loader } = fixture();
  const url = await loader.loadItem(item);
  assert.equal(await loader.loadItem(item), url);
  assert.equal(await loader.loadItem(item), url);
  loader.unloadItem(item);
  loader.unloadItem(item);
  assert.equal(await (await fetch(url)).text(), "chapter");
  loader.unloadItem(item);
  await assert.rejects(fetch(url));
});

test("concurrent root owners share a single creation and each retains a reference", async () => {
  const { loader, loads } = fixture();
  const urls = await Promise.all([
    loader.loadItem(item),
    loader.loadItem(item),
    loader.loadItem(item),
  ]);
  assert.equal(new Set(urls).size, 1);
  assert.equal(loads(), 1);
  loader.unloadItem(item);
  loader.unloadItem(item);
  assert.equal((await fetch(urls[0])).status, 200);
  loader.unloadItem(item);
  await assert.rejects(fetch(urls[0]));
});

test("a released resource can be acquired again without reusing a revoked URL", async () => {
  const { loader } = fixture();
  const first = await loader.loadItem(item);
  loader.unloadItem(item);
  const second = await loader.loadItem(item);
  assert.notEqual(second, first);
  assert.equal(await (await fetch(second)).text(), "chapter");
  loader.unloadItem(item);
});
