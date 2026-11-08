import fs from "node:fs/promises";
import http from "node:http";
import path from "node:path";
import assert from "node:assert/strict";

const root = path.resolve(
  path.dirname(new URL(import.meta.url).pathname),
  "..",
);
const assets = path.join(root, "app/src/main/assets");
if (!process.argv[2] || !process.argv[3])
  throw new Error("Usage: node tools/verify-reader-browser.mjs <epub> <ttf>");
const book = await fs.readFile(process.argv[2]);
const font = await fs.readFile(process.argv[3]);
const server = http.createServer(async (req, res) => {
  try {
    if (req.url === "/") {
      res.setHeader("Content-Type", "text/html");
      res.end(
        '<html><style>html,body,#reader{margin:0;width:100%;height:100%;overflow:hidden}</style><div id="reader"></div><script>window.events=[];window.ReactNativeWebView={postMessage:data=>events.push(JSON.parse(data))}</script><script type="module" src="/reader/bridge.js"></script></html>',
      );
      return;
    }
    const file = path.resolve(
      assets,
      "." + decodeURIComponent(new URL(req.url, "http://localhost").pathname),
    );
    if (!file.startsWith(assets + "/")) {
      res.writeHead(403).end();
      return;
    }
    res.setHeader(
      "Content-Type",
      file.endsWith(".js") ? "text/javascript" : "text/html",
    );
    res.end(await fs.readFile(file));
  } catch {
    res.writeHead(404).end();
  }
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const target = await (
  await fetch("http://127.0.0.1:9224/json/new?" + encodeURIComponent(origin), {
    method: "PUT",
  })
).json();
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.onopen = resolve;
  socket.onerror = reject;
});
let seq = 0;
const pending = new Map();
const cdp = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
socket.onmessage = async (event) => {
  const msg = JSON.parse(event.data);
  if (msg.method === "Fetch.requestPaused") {
    await cdp("Fetch.fulfillRequest", {
      requestId: msg.params.requestId,
      responseCode: 200,
      responseHeaders: [
        { name: "Content-Type", value: "font/ttf" },
        { name: "Access-Control-Allow-Origin", value: "*" },
      ],
      body: font.toString("base64"),
    });
    return;
  }
  const p = pending.get(msg.id);
  if (!p) return;
  pending.delete(msg.id);
  if (msg.error) p.reject(new Error(msg.error.message));
  else p.resolve(msg.result);
};
const evaluate = async (expression) => {
  const result = await cdp("Runtime.evaluate", {
    expression,
    awaitPromise: true,
    returnByValue: true,
  });
  if (result.exceptionDetails)
    throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
};
const until = async (expression) => {
  for (let i = 0; i < 100; i++) {
    if (await evaluate(expression)) return;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error("Timed out " + expression);
};
try {
  await cdp("Emulation.setDeviceMetricsOverride", {
    width: 412,
    height: 915,
    deviceScaleFactor: 1,
    mobile: true,
  });
  await cdp("Fetch.enable", {
    patterns: [
      { urlPattern: "https://appassets.androidplatform.net/reader-fonts/*" },
    ],
  });
  await until('window.events?.some(e=>e.type==="ready")');
  const settings = {
    fontSize: 22,
    lineHeight: 1.8,
    fontFamily: "__serverfont_test",
    fontWeight: 400,
    paragraphSpacing: 0.8,
    letterSpacing: 0.1,
    paragraphIndent: true,
    sidePadding: 20,
    isDark: false,
    themeName: "sepia",
    flow: "paginated",
  };
  await evaluate(
    `window.__readerBegin(${JSON.stringify(JSON.stringify({ format: "epub", cfi: null, fraction: 0.05, settings }))})`,
  );
  for (let i = 0; i < book.length; i += 192000)
    await evaluate(
      `window.__readerChunk(${JSON.stringify(book.subarray(i, i + 192000).toString("base64"))})`,
    );
  await evaluate("window.__readerCommit()");
  await until('window.events.some(e=>e.type==="loaded")');
  const faces = [
    {
      family: "__serverfont_test",
      fileName: "server-1-4321-aabbccddeeff.ttf",
      format: "ttf",
      weight: 400,
      weightMin: null,
      weightMax: null,
      style: "normal",
    },
  ];
  await evaluate(
    `window.__readerCommand(${JSON.stringify(JSON.stringify({ type: "setFonts", faces }))})`,
  );
  const result = await evaluate(`(async()=>{
    const view=document.querySelector('foliate-view');
    const doc=view.renderer.getContents()[0].doc;
    const loaded=await doc.fonts.load('22px "__serverfont_test"');
    const style=doc.defaultView.getComputedStyle(doc.body);
    return {loaded:window.events.some(e=>e.type==='loaded'),errors:window.events.filter(e=>e.type==='error'),loadedFonts:loaded.length,fontFamily:style.fontFamily,fontSize:style.fontSize,lineHeight:style.lineHeight,faceCount:doc.fonts.size};
  })()`);
  assert.equal(result.loadedFonts, 1);
  assert.match(result.fontFamily, /__serverfont_test/);
  assert.equal(result.fontSize, "22px");
  assert.equal(result.errors.length, 0);
  assert.equal(result.lineHeight, "39.6px");
  console.log(JSON.stringify(result, null, 2));
} finally {
  socket.close();
  await fetch("http://127.0.0.1:9224/json/close/" + target.id);
  server.close();
}
