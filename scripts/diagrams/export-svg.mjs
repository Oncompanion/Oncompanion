// Exports .excalidraw diagrams to .svg, like excalidraw.com's
// "Export image → SVG" with "Embed scene" off and "Background" on.
//
// Usage (from scripts/diagrams, after `npm ci`):
//   npm run export                                          # every docs/architecture-*.excalidraw
//   npm run export -- ../../docs/architecture-symptoms.excalidraw
//
// Each .svg is written next to its .excalidraw file.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";

// @excalidraw/utils draws into a DOM, so give Node a fake browser first.
const dom = new JSDOM("<!doctype html><html><body></body></html>", { pretendToBeVisual: true });
for (const name of [
  "window",
  "document",
  "navigator",
  "DOMParser",
  "Node",
  "Element",
  "HTMLElement",
  "SVGElement",
  "Image",
  "HTMLCanvasElement",
  "XMLSerializer",
  "devicePixelRatio",
]) {
  if (globalThis[name] === undefined) {
    Object.defineProperty(globalThis, name, { value: dom.window[name], configurable: true });
  }
}
globalThis.FontFace ??= class {
  constructor(family) {
    this.family = family;
  }
  load() {
    return Promise.resolve(this);
  }
};
dom.window.document.fonts ??= {
  add() {},
  check: () => true,
  load: () => Promise.resolve([]),
  ready: Promise.resolve(),
  *[Symbol.iterator]() {},
};

const { exportToSvg } = await import("@excalidraw/utils");

// Default to every architecture diagram in docs/.
const docsDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../docs");
const inputs =
  process.argv.length > 2
    ? process.argv.slice(2)
    : fs
        .readdirSync(docsDir)
        .filter((file) => /^architecture-.*\.excalidraw$/.test(file))
        .map((file) => path.join(docsDir, file));

for (const input of inputs) {
  const scene = JSON.parse(fs.readFileSync(input, "utf8"));
  const svg = await exportToSvg({
    data: {
      elements: scene.elements,
      appState: { ...scene.appState, exportBackground: true, exportEmbedScene: false },
      files: scene.files ?? {},
    },
    // Otherwise the fonts are downloaded and inlined; GitHub doesn't load SVG fonts anyway.
    config: { skipInliningFonts: true },
  });
  const output = input.replace(/\.excalidraw$/, ".svg");
  fs.writeFileSync(output, svg.outerHTML + "\n");
  console.log(`${path.basename(input)} → ${path.basename(output)}`);
}
