const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const renderMath = require("../site/render_math.js");

function rasterizeFitFrame({ viewerWidth, viewerHeight, imageWidth, imageHeight, markerX }) {
  const zoom = renderMath.fitZoom(viewerWidth, viewerHeight, imageWidth, imageHeight);
  const scaledWidth = imageWidth * zoom;
  const scaledHeight = imageHeight * zoom;
  const offsetX = renderMath.clampAxisOffset((viewerWidth - scaledWidth) / 2, viewerWidth, scaledWidth);
  const offsetY = renderMath.clampAxisOffset((viewerHeight - scaledHeight) / 2, viewerHeight, scaledHeight);
  const pixels = Array.from({ length: viewerHeight }, () => Array(viewerWidth).fill(0));
  const left = Math.round(offsetX);
  const top = Math.round(offsetY);
  const width = Math.round(scaledWidth);
  const height = Math.round(scaledHeight);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const px = left + x;
      const py = top + y;
      if (px >= 0 && py >= 0 && px < viewerWidth && py < viewerHeight) pixels[py][px] = 1;
    }
  }
  const markerScreenX = Math.round(offsetX + markerX * zoom);
  return { zoom, scaledWidth, scaledHeight, offsetX, offsetY, pixels, markerScreenX };
}

test("fit view centers narrow maps instead of pinning them to the left edge", () => {
  const frame = rasterizeFitFrame({
    viewerWidth: 20,
    viewerHeight: 20,
    imageWidth: 10,
    imageHeight: 20,
    markerX: 9,
  });

  assert.equal(frame.zoom, 1);
  assert.equal(frame.offsetX, 5);
  assert.equal(frame.offsetY, 0);
  assert.equal(frame.markerScreenX, 14);
  assert.equal(frame.pixels[10][4], 0);
  assert.equal(frame.pixels[10][5], 1);
  assert.equal(frame.pixels[10][14], 1);
  assert.equal(frame.pixels[10][15], 0);
});

test("fit view preserves the bottom-right edge when the full map is visible", () => {
  const frame = rasterizeFitFrame({
    viewerWidth: 21,
    viewerHeight: 21,
    imageWidth: 10,
    imageHeight: 20,
    markerX: 9,
  });

  assert.equal(frame.offsetX, 5.5);
  assert.equal(frame.offsetY, 0.5);
  assert.equal(frame.pixels[20][15], 1);
  assert.equal(frame.pixels[20][16], 0);
});

test("render math keeps overlay alpha composition deterministic", () => {
  assert.deepEqual(
    renderMath.compositeStraightAlpha([16, 24, 32], [201, 127, 58, 166]),
    [136, 91, 49],
  );
});

test("webgl renderer source disables browser-managed colour shifts for overlay tiles", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../site/app.js"), "utf8");
  assert.match(appJs, /colorSpaceConversion:\s*"none"/);
  assert.match(appJs, /premultiplyAlpha:\s*"none"/);
  assert.match(appJs, /gl\.disable\(gl\.DITHER\)/);
  assert.match(appJs, /UNPACK_COLORSPACE_CONVERSION_WEBGL/);
  assert.doesNotMatch(appJs, /outside generated world/);
});

test("measurement drag uses client coordinates and no longer depends on removed faux scrollbars", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../site/app.js"), "utf8");
  const indexHtml = fs.readFileSync(path.join(__dirname, "../site/index.html"), "utf8");
  const stylesCss = fs.readFileSync(path.join(__dirname, "../site/styles.css"), "utf8");

  assert.match(appJs, /Math\.abs\(current\.clientX - start\.clientX\) \+ Math\.abs\(current\.clientY - start\.clientY\) >= 4/);
  assert.match(appJs, /updateMeasurement\(event\);\s*state\.measurePointerId = null;\s*if \(!state\.measureMoved\) copyCoordinates\(event\);/);
  assert.doesNotMatch(appJs, /screenX|screenY|scrollX|scrollY|updateScrollbars/);
  assert.doesNotMatch(indexHtml, /scrollbar scrollbar-[xy]/);
  assert.match(indexHtml, /viewport-fit=cover/);
  assert.match(stylesCss, /position:\s*fixed;/);
  assert.match(stylesCss, /safe-area-inset-bottom/);
});

test("published root page includes the current hover map shell", () => {
  const rootIndexHtml = fs.readFileSync(path.join(__dirname, "../../../index.html"), "utf8");
  const appJs = fs.readFileSync(path.join(__dirname, "../site/app.js"), "utf8");

  assert.match(rootIndexHtml, /viewport-fit=cover/);
  assert.match(rootIndexHtml, /id="animal-controls"/);
  assert.match(rootIndexHtml, /id="weather-controls"/);
  assert.match(rootIndexHtml, /Open-Meteo/);
  assert.match(rootIndexHtml, /render_math\.js/);
  assert.doesNotMatch(rootIndexHtml, /scrollbar scrollbar-[xy]/);
  assert.match(rootIndexHtml, /Left drag measures distance/);
  assert.match(appJs, /if \(!controls\) \{/);
});

test("live weather controls work with older manifests and fetch Open-Meteo", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../site/app.js"), "utf8");
  const indexHtml = fs.readFileSync(path.join(__dirname, "../site/index.html"), "utf8");

  assert.match(indexHtml, /id="weather-controls"/);
  assert.match(appJs, /function resolveLiveWeatherConfig\(manifest\)/);
  assert.match(appJs, /function buildLiveWeatherGrid\(manifest, requestedColumns\)/);
  assert.match(appJs, /function britishNationalGridToWgs84\(easting, northing\)/);
  assert.match(appJs, /https:\/\/api\.open-meteo\.com\/v1\/forecast/);
  assert.match(appJs, /url\.searchParams\.set\("current", "cloud_cover"\)/);
  assert.match(appJs, /url\.searchParams\.set\("hourly", "precipitation_probability"\)/);
  assert.match(appJs, /label: "Cloud coverage"/);
  assert.match(appJs, /label: "Rain \/ precipitation"/);
});

test("site fit view uses the full exported image and treats outside-height ocean as y=62", () => {
  const appJs = fs.readFileSync(path.join(__dirname, "../site/app.js"), "utf8");

  assert.match(
    appJs,
    /function viewerFitBounds\(\)\s*\{\s*return \{ left: 0, top: 0, right: state\.imageWidth, bottom: state\.imageHeight \};\s*\}/,
  );
  assert.match(appJs, /function heightDataBounds\(\)/);
  assert.match(appJs, /function isInsideHeightDataBounds\(imageX, imageY\)/);
  assert.match(
    appJs,
    /if \(!isInsideHeightDataBounds\(image\.x, image\.y\)\) \{\s*return \{\s*\.\.\.samplePoint,\s*height: null,\s*minecraftHeight: 62,\s*\};\s*\}/,
  );
});
