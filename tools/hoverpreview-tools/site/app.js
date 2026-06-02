const FORMAT = "ukgeo-hoverpreviews-v1";
const DEFAULT_MANIFEST = "hoverpreviews/hover_manifest.json";
const START_STATUS = "Mouse wheel zooms. Middle/right drag pans. Left click copies the current Minecraft coordinates.";
const DEFAULT_VISIBLE_OVERLAYS = new Set(["surface", "vegetation", "rivers"]);
const DEFAULT_VISIBLE_ORES = new Set(["coal", "iron", "copper", "zinc", "gold"]);
const MAX_DEVICE_PIXEL_RATIO = 2;
const MIN_DECODE_PADDING_PIXELS = 192;
const MIN_MAP_ZOOM = 0.09;
const MAX_DISPLAY_ZOOM_PERCENT = 500;
const MAX_MAP_ZOOM = MIN_MAP_ZOOM + MAX_DISPLAY_ZOOM_PERCENT / 100;
const WHEEL_DELTA_PER_ZOOM_STEP = 100;
const PINCH_PIXELS_PER_ZOOM_STEP = 36;
const PINCH_DISTANCE_DEADZONE_PIXELS = 4;

const elements = {
  loadState: document.querySelector("#load-state"),
  layerControls: document.querySelector("#layer-controls"),
  oreControls: document.querySelector("#ore-controls"),
  viewer: document.querySelector("#viewer"),
  stack: document.querySelector("#map-stack"),
  empty: document.querySelector("#empty-state"),
  status: document.querySelector("#status"),
  zoomIn: document.querySelector("#zoom-in"),
  zoomOut: document.querySelector("#zoom-out"),
  zoomFit: document.querySelector("#zoom-fit"),
  zoomLabel: document.querySelector("#zoom-label"),
  scrollX: document.querySelector(".scrollbar-x span"),
  scrollY: document.querySelector(".scrollbar-y span"),
};

const state = {
  manifest: null,
  manifestUrl: null,
  baseUrl: null,
  imageWidth: 0,
  imageHeight: 0,
  zoom: 1,
  offsetX: 0,
  offsetY: 0,
  panPointerId: null,
  panStartX: 0,
  panStartY: 0,
  panOffsetX: 0,
  panOffsetY: 0,
  measurePointerId: null,
  measureStart: null,
  measureMoved: false,
  layers: new Map(),
  layerBitmaps: new Map(),
  layerLoads: new Map(),
  samples: new Map(),
  sampleLoads: new Map(),
  measure: null,
  mapCanvas: null,
  mapCtx: null,
  renderRequest: 0,
  wheelZoomDelta: 0,
  wheelZoomPrecise: false,
  touchPointers: new Map(),
  pinchDistance: null,
  pinchCenterX: null,
  pinchCenterY: null,
  pinchRemainder: 0,
};

elements.zoomIn.addEventListener("click", (event) => stepZoomAroundCentre(1, event.shiftKey));
elements.zoomOut.addEventListener("click", (event) => stepZoomAroundCentre(-1, event.shiftKey));
elements.zoomFit.addEventListener("click", fitView);
elements.zoomLabel.addEventListener("focus", () => elements.zoomLabel.select());
elements.zoomLabel.addEventListener("blur", commitZoomInput);
elements.zoomLabel.addEventListener("change", commitZoomInput);
elements.zoomLabel.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    commitZoomInput();
    elements.zoomLabel.blur();
  } else if (event.key === "Escape") {
    updateZoomInput();
    elements.zoomLabel.blur();
  }
});
elements.zoomLabel.addEventListener("wheel", (event) => {
  event.preventDefault();
  stepZoomAroundCentre(event.deltaY < 0 ? 1 : -1, event.shiftKey);
}, { passive: false });

elements.viewer.addEventListener("contextmenu", (event) => event.preventDefault());
elements.viewer.addEventListener("wheel", (event) => {
  event.preventDefault();
  handleWheelZoom(event);
}, { passive: false });

elements.viewer.addEventListener("pointerdown", (event) => {
  if (!state.manifest) return;
  elements.viewer.setPointerCapture(event.pointerId);
  if (event.pointerType === "touch") {
    state.touchPointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (state.touchPointers.size >= 2) {
      beginPinch();
      cancelPointerInteractions();
      event.preventDefault();
      return;
    }
  }
  if (event.button === 1 || event.button === 2) {
    state.panPointerId = event.pointerId;
    state.panStartX = event.clientX;
    state.panStartY = event.clientY;
    state.panOffsetX = state.offsetX;
    state.panOffsetY = state.offsetY;
    elements.viewer.classList.add("dragging");
    event.preventDefault();
    return;
  }
  if (event.button === 0) {
    state.measurePointerId = event.pointerId;
    state.measureStart = sampleFromEvent(event);
    state.measureMoved = false;
    clearMeasurement();
  }
});

elements.viewer.addEventListener("pointermove", (event) => {
  if (!state.manifest) return;
  if (event.pointerType === "touch" && state.touchPointers.has(event.pointerId)) {
    state.touchPointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (state.touchPointers.size >= 2) {
      updatePinchGesture();
      event.preventDefault();
      return;
    }
  }
  if (state.panPointerId === event.pointerId) {
    state.offsetX = state.panOffsetX + event.clientX - state.panStartX;
    state.offsetY = state.panOffsetY + event.clientY - state.panStartY;
    applyTransform();
  } else if (state.measurePointerId === event.pointerId) {
    updateMeasurement(event);
  }
  updateStatus(event);
});

elements.viewer.addEventListener("pointerup", (event) => {
  if (!state.manifest) return;
  if (event.pointerType === "touch") {
    state.touchPointers.delete(event.pointerId);
    if (state.touchPointers.size < 2) resetPinch();
  }
  if (state.panPointerId === event.pointerId) {
    state.panPointerId = null;
    elements.viewer.classList.remove("dragging");
    return;
  }
  if (state.measurePointerId === event.pointerId) {
    state.measurePointerId = null;
    if (!state.measureMoved) copyCoordinates(event);
  }
});

elements.viewer.addEventListener("pointercancel", (event) => {
  if (event.pointerType === "touch") {
    state.touchPointers.delete(event.pointerId);
    if (state.touchPointers.size < 2) resetPinch();
  }
  if (state.panPointerId === event.pointerId) state.panPointerId = null;
  if (state.measurePointerId === event.pointerId) state.measurePointerId = null;
  elements.viewer.classList.remove("dragging");
});

elements.viewer.addEventListener("pointerleave", () => {
  if (!state.manifest) return;
  state.touchPointers.clear();
  resetPinch();
  setStatus(START_STATUS);
});

window.addEventListener("resize", () => {
  if (state.manifest) applyTransform();
});

loadManifest(defaultManifest()).catch((error) => {
  if (elements.loadState) elements.loadState.textContent = "Preview data not found";
  setStatus(`No hover preview found at ${defaultManifest()}: ${error.message}`);
});

function defaultManifest() {
  const manifest = new URLSearchParams(location.search).get("manifest");
  if (manifest) return manifest;
  return document.body.dataset.defaultManifest || DEFAULT_MANIFEST;
}

async function loadManifest(url) {
  if (elements.loadState) elements.loadState.textContent = `Loading ${url}...`;
  setStatus(`Loading ${url}...`);
  const response = await fetch(url, { cache: "no-cache" });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
  const manifest = await response.json();
  if (manifest.format !== FORMAT) throw new Error(`Unsupported hover preview format: ${manifest.format || "missing"}`);

  state.manifest = manifest;
  state.manifestUrl = new URL(url, location.href);
  state.baseUrl = new URL(".", state.manifestUrl);
  state.imageWidth = Number(manifest.image_width);
  state.imageHeight = Number(manifest.image_height);
  state.layers.clear();
  clearBitmapCaches();
  clearMeasurement();
  elements.stack.replaceChildren();
  elements.layerControls.replaceChildren();
  elements.oreControls.replaceChildren();

  state.mapCanvas = document.createElement("canvas");
  state.mapCanvas.className = "map-canvas";
  state.mapCtx = state.mapCanvas.getContext("2d", { alpha: false });
  state.mapCtx.imageSmoothingEnabled = true;
  elements.stack.append(state.mapCanvas);

  for (const layer of manifest.layers || []) {
    addLayer(layer);
  }

  const height = state.layers.get("height");
  if (height) loadSample(height.layer).catch(() => undefined);

  elements.empty.hidden = true;
  fitView();
  if (elements.loadState) elements.loadState.textContent = `Loaded ${(manifest.layers || []).length} layers`;
  setStatus(START_STATUS);
}

function addLayer(layer) {
  const enabled = isLayerVisibleByDefault(layer);
  state.layers.set(layer.name, { layer, enabled });
  const controls = layer.kind === "ore" ? elements.oreControls : elements.layerControls;
  controls.append(toggleFor(layer, enabled));
}

function isLayerVisibleByDefault(layer) {
  if (layer.kind === "base") return true;
  if (layer.kind === "ore") return DEFAULT_VISIBLE_ORES.has(layer.ore || labelFor(layer.name));
  return DEFAULT_VISIBLE_OVERLAYS.has(layer.name);
}

function toggleFor(layer, checked) {
  const label = document.createElement("label");
  label.className = "layer-toggle";
  const input = document.createElement("input");
  input.type = "checkbox";
  input.checked = checked;
  input.addEventListener("change", () => {
    const entry = state.layers.get(layer.name);
    entry.enabled = input.checked;
    if (!input.checked) {
      releaseLayerBitmaps(layer.name);
      releaseSample(layer.name);
    }
    scheduleRender();
  });
  const name = document.createElement("span");
  name.textContent = layer.ore || labelFor(layer.name);
  label.append(input, name);
  return label;
}

function labelFor(name) {
  return name.replace(/^ore:/, "").replaceAll("_", " ");
}

function chooseMip(layer) {
  const mips = layer.mips || [{ factor: 1, file: layer.file, width: state.imageWidth, height: state.imageHeight }];
  const idealFactor = Math.max(1, Math.floor(1 / Math.max(state.zoom, 0.001)));
  let chosen = mips[0];
  for (const mip of mips) {
    if (Number(mip.factor) <= idealFactor) chosen = mip;
  }
  return chosen;
}

function layerUrl(layer, mip) {
  return new URL(mip.file || layer.file, state.baseUrl).href;
}

function layerCacheKey(layer, mip) {
  return `${layer.name}\n${Number(mip.factor) || 1}\n${mip.file || layer.file}`;
}

function layerRegionCacheKey(layer, mip, region) {
  return `${layerCacheKey(layer, mip)}\n${region.left},${region.top},${region.right},${region.bottom}`;
}

function fitView() {
  if (!state.manifest) return;
  const rect = elements.viewer.getBoundingClientRect();
  state.zoom = clampZoom(Math.min(1, rect.width / state.imageWidth, rect.height / state.imageHeight));
  state.offsetX = 0;
  state.offsetY = 0;
  applyTransform();
  updateZoomInput();
}

function stepZoomAroundCentre(direction, precise) {
  const rect = elements.viewer.getBoundingClientRect();
  stepZoomAt(rect.left + rect.width / 2, rect.top + rect.height / 2, direction, precise);
}

function cancelPointerInteractions() {
  state.panPointerId = null;
  state.measurePointerId = null;
  state.measureMoved = false;
  elements.viewer.classList.remove("dragging");
  clearMeasurement();
}

function beginPinch() {
  const gesture = pinchGesture();
  if (!gesture) return;
  state.pinchDistance = gesture.distance;
  state.pinchCenterX = gesture.centerX;
  state.pinchCenterY = gesture.centerY;
  state.pinchRemainder = 0;
}

function updatePinchGesture() {
  const gesture = pinchGesture();
  if (!gesture) return;
  if (state.pinchDistance === null) {
    beginPinch();
    return;
  }
  state.offsetX += gesture.centerX - state.pinchCenterX;
  state.offsetY += gesture.centerY - state.pinchCenterY;
  state.pinchCenterX = gesture.centerX;
  state.pinchCenterY = gesture.centerY;
  const distanceDelta = gesture.distance - state.pinchDistance;
  state.pinchDistance = gesture.distance;
  if (Math.abs(distanceDelta) >= PINCH_DISTANCE_DEADZONE_PIXELS) {
    state.pinchRemainder += distanceDelta;
  }
  const steps = Math.trunc(state.pinchRemainder / PINCH_PIXELS_PER_ZOOM_STEP);
  if (steps !== 0) {
    state.pinchRemainder -= steps * PINCH_PIXELS_PER_ZOOM_STEP;
    setDisplayZoomAt(displayZoomPercent() + steps, gesture.centerX, gesture.centerY);
    return;
  }
  applyTransform();
}

function resetPinch() {
  state.pinchDistance = null;
  state.pinchCenterX = null;
  state.pinchCenterY = null;
  state.pinchRemainder = 0;
}

function pinchGesture() {
  const points = Array.from(state.touchPointers.values());
  if (points.length < 2) return null;
  const [a, b] = points;
  return {
    distance: Math.hypot(a.x - b.x, a.y - b.y),
    centerX: (a.x + b.x) / 2,
    centerY: (a.y + b.y) / 2,
  };
}

function handleWheelZoom(event) {
  if (!state.manifest) return;
  if (state.wheelZoomPrecise !== event.shiftKey) {
    state.wheelZoomDelta = 0;
    state.wheelZoomPrecise = event.shiftKey;
  }
  const modeMultiplier = event.deltaMode === WheelEvent.DOM_DELTA_PAGE ? 800 : event.deltaMode === WheelEvent.DOM_DELTA_LINE ? 16 : 1;
  state.wheelZoomDelta += event.deltaY * modeMultiplier;
  if (Math.abs(state.wheelZoomDelta) < WHEEL_DELTA_PER_ZOOM_STEP) return;
  const direction = state.wheelZoomDelta < 0 ? 1 : -1;
  state.wheelZoomDelta = 0;
  stepZoomAt(event.clientX, event.clientY, direction, event.shiftKey);
}

function stepZoomAt(clientX, clientY, direction, precise) {
  const current = displayZoomPercent();
  setDisplayZoomAt(nextZoomPercent(current, direction, precise), clientX, clientY);
}

function nextZoomPercent(percent, direction, precise) {
  if (precise) return percent + direction;
  if (direction > 0) {
    if (percent < 100) return Math.min(100, Math.floor(percent / 10) * 10 + 10);
    return Math.floor(percent / 50) * 50 + 50;
  }
  if (percent > 100) return Math.max(100, Math.ceil(percent / 50) * 50 - 50);
  return Math.ceil(percent / 10) * 10 - 10;
}

function setDisplayZoomAt(percent, clientX, clientY) {
  if (!state.manifest) return;
  const rect = elements.viewer.getBoundingClientRect();
  const before = screenToImage(clientX, clientY);
  state.zoom = zoomForDisplayPercent(percent);
  state.offsetX = clientX - rect.left - before.x * state.zoom;
  state.offsetY = clientY - rect.top - before.y * state.zoom;
  applyTransform();
  updateZoomInput();
}

function clampZoom(zoom) {
  return Math.max(MIN_MAP_ZOOM, Math.min(MAX_MAP_ZOOM, zoom));
}

function displayZoomPercent() {
  return Math.max(0, Math.min(MAX_DISPLAY_ZOOM_PERCENT, Math.round((state.zoom - MIN_MAP_ZOOM) * 100)));
}

function zoomForDisplayPercent(percent) {
  const cleanPercent = Number.isFinite(percent) ? percent : displayZoomPercent();
  return clampZoom(MIN_MAP_ZOOM + Math.max(0, Math.min(MAX_DISPLAY_ZOOM_PERCENT, cleanPercent)) / 100);
}

function commitZoomInput() {
  const value = parseZoomPercent(elements.zoomLabel.value);
  const rect = elements.viewer.getBoundingClientRect();
  setDisplayZoomAt(value, rect.left + rect.width / 2, rect.top + rect.height / 2);
}

function parseZoomPercent(value) {
  const parsed = Number.parseFloat(String(value).replace("%", "").trim());
  if (!Number.isFinite(parsed)) return displayZoomPercent();
  return Math.max(0, Math.min(MAX_DISPLAY_ZOOM_PERCENT, Math.round(parsed)));
}

function updateZoomInput() {
  elements.zoomLabel.value = `${displayZoomPercent()}%`;
}

function applyTransform() {
  const rect = elements.viewer.getBoundingClientRect();
  const scaledWidth = state.imageWidth * state.zoom;
  const scaledHeight = state.imageHeight * state.zoom;
  const minX = Math.min(0, rect.width - scaledWidth);
  const minY = Math.min(0, rect.height - scaledHeight);
  state.offsetX = Math.min(0, Math.max(minX, state.offsetX));
  state.offsetY = Math.min(0, Math.max(minY, state.offsetY));
  if (document.activeElement !== elements.zoomLabel) updateZoomInput();
  updateScrollbars(rect, scaledWidth, scaledHeight);
  updateMeasurementOverlay();
  scheduleRender();
}

function updateScrollbars(rect, scaledWidth, scaledHeight) {
  const xRatio = rect.width / Math.max(scaledWidth, rect.width);
  const yRatio = rect.height / Math.max(scaledHeight, rect.height);
  const xTrack = rect.width;
  const yTrack = rect.height;
  const xThumb = Math.max(18, xTrack * xRatio);
  const yThumb = Math.max(18, yTrack * yRatio);
  const xTravel = Math.max(0, xTrack - xThumb - 2);
  const yTravel = Math.max(0, yTrack - yThumb - 2);
  const xOffset = scaledWidth <= rect.width ? 0 : (-state.offsetX / (scaledWidth - rect.width)) * xTravel;
  const yOffset = scaledHeight <= rect.height ? 0 : (-state.offsetY / (scaledHeight - rect.height)) * yTravel;
  elements.scrollX.style.width = `${xThumb}px`;
  elements.scrollX.style.left = `${1 + xOffset}px`;
  elements.scrollY.style.height = `${yThumb}px`;
  elements.scrollY.style.top = `${1 + yOffset}px`;
}

function scheduleRender() {
  if (state.renderRequest) return;
  state.renderRequest = requestAnimationFrame(() => {
    state.renderRequest = 0;
    renderViewport();
  });
}

function renderViewport() {
  if (!state.manifest || !state.mapCanvas || !state.mapCtx) return;
  const rect = elements.viewer.getBoundingClientRect();
  const dpr = Math.min(MAX_DEVICE_PIXEL_RATIO, window.devicePixelRatio || 1);
  const canvasWidth = Math.max(1, Math.floor(rect.width * dpr));
  const canvasHeight = Math.max(1, Math.floor(rect.height * dpr));
  if (state.mapCanvas.width !== canvasWidth || state.mapCanvas.height !== canvasHeight) {
    state.mapCanvas.width = canvasWidth;
    state.mapCanvas.height = canvasHeight;
    state.mapCanvas.style.width = `${rect.width}px`;
    state.mapCanvas.style.height = `${rect.height}px`;
  }

  const ctx = state.mapCtx;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, rect.width, rect.height);
  ctx.fillStyle = "#222222";
  ctx.fillRect(0, 0, rect.width, rect.height);

  const crop = visibleImageCrop(rect);
  if (!crop) return;

  const activeBitmapKeys = new Set();
  for (const entry of state.layers.values()) {
    if (!entry.enabled) continue;
    const mip = chooseMip(entry.layer);
    const decoded = bitmapForLayer(entry.layer, mip, crop);
    if (!decoded) continue;
    activeBitmapKeys.add(decoded.key);
    drawLayerBitmap(ctx, decoded, crop);
  }
  releaseUnusedLayerBitmaps(activeBitmapKeys);
}

function visibleImageCrop(rect) {
  const left = Math.max(0, -state.offsetX / state.zoom);
  const top = Math.max(0, -state.offsetY / state.zoom);
  const right = Math.min(state.imageWidth, (rect.width - state.offsetX) / state.zoom);
  const bottom = Math.min(state.imageHeight, (rect.height - state.offsetY) / state.zoom);
  if (right <= left || bottom <= top) return null;
  return { left, top, right, bottom };
}

function bitmapForLayer(layer, mip, crop) {
  const existing = cachedLayerRegion(layer, mip, crop);
  if (existing) return existing;
  const region = layerDecodeRegion(crop, mip);
  const key = layerRegionCacheKey(layer, mip, region);
  if (!state.layerLoads.has(key)) {
    const load = loadLayerBitmap(layer, mip, region, key).finally(() => state.layerLoads.delete(key));
    state.layerLoads.set(key, load);
  }
  return null;
}

function cachedLayerRegion(layer, mip, crop) {
  const prefix = `${layerCacheKey(layer, mip)}\n`;
  const visible = mipVisibleRegion(crop, mip);
  for (const [key, decoded] of state.layerBitmaps) {
    if (!key.startsWith(prefix)) continue;
    if (containsRegion(decoded.region, visible)) return decoded;
  }
  return null;
}

function mipVisibleRegion(crop, mip) {
  const factor = Number(mip.factor) || 1;
  const width = Number(mip.width) || Math.ceil(state.imageWidth / factor);
  const height = Number(mip.height) || Math.ceil(state.imageHeight / factor);
  return {
    left: Math.max(0, Math.floor(crop.left / factor)),
    top: Math.max(0, Math.floor(crop.top / factor)),
    right: Math.min(width, Math.ceil(crop.right / factor)),
    bottom: Math.min(height, Math.ceil(crop.bottom / factor)),
  };
}

function layerDecodeRegion(crop, mip) {
  const visible = mipVisibleRegion(crop, mip);
  const width = Number(mip.width) || Math.ceil(state.imageWidth / (Number(mip.factor) || 1));
  const height = Number(mip.height) || Math.ceil(state.imageHeight / (Number(mip.factor) || 1));
  const visibleWidth = Math.max(1, visible.right - visible.left);
  const visibleHeight = Math.max(1, visible.bottom - visible.top);
  const padX = Math.max(MIN_DECODE_PADDING_PIXELS, Math.ceil(visibleWidth * 0.5));
  const padY = Math.max(MIN_DECODE_PADDING_PIXELS, Math.ceil(visibleHeight * 0.5));
  const snapX = Math.max(MIN_DECODE_PADDING_PIXELS, Math.ceil(visibleWidth * 0.5));
  const snapY = Math.max(MIN_DECODE_PADDING_PIXELS, Math.ceil(visibleHeight * 0.5));
  const left = Math.max(0, Math.floor((visible.left - padX) / snapX) * snapX);
  const top = Math.max(0, Math.floor((visible.top - padY) / snapY) * snapY);
  return {
    left,
    top,
    right: Math.min(width, Math.max(visible.right + padX, left + visibleWidth)),
    bottom: Math.min(height, Math.max(visible.bottom + padY, top + visibleHeight)),
  };
}

function containsRegion(outer, inner) {
  return outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom;
}

async function loadLayerBitmap(layer, mip, region, key) {
  const response = await fetch(layerUrl(layer, mip), { cache: "force-cache" });
  if (!response.ok) return;
  const blob = await response.blob();
  const bitmap = await createImageBitmap(blob, region.left, region.top, region.right - region.left, region.bottom - region.top);
  const entry = state.layers.get(layer.name);
  if (!entry?.enabled || !key.startsWith(`${layerCacheKey(layer, chooseMip(layer))}\n`)) {
    if (typeof bitmap.close === "function") bitmap.close();
    return;
  }
  state.layerBitmaps.set(key, { key, bitmap, mip, region });
  scheduleRender();
}

function drawLayerBitmap(ctx, decoded, crop) {
  const factor = Number(decoded.mip.factor) || 1;
  const sx = crop.left / factor - decoded.region.left;
  const sy = crop.top / factor - decoded.region.top;
  const sw = (crop.right - crop.left) / factor;
  const sh = (crop.bottom - crop.top) / factor;
  const dx = state.offsetX + crop.left * state.zoom;
  const dy = state.offsetY + crop.top * state.zoom;
  const dw = (crop.right - crop.left) * state.zoom;
  const dh = (crop.bottom - crop.top) * state.zoom;
  ctx.drawImage(decoded.bitmap, sx, sy, sw, sh, dx, dy, dw, dh);
}

function releaseLayerBitmaps(layerName) {
  for (const [key, decoded] of state.layerBitmaps) {
    if (!key.startsWith(`${layerName}\n`)) continue;
    if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();
    state.layerBitmaps.delete(key);
  }
}

function releaseUnusedLayerBitmaps(activeKeys) {
  for (const [key, decoded] of state.layerBitmaps) {
    if (activeKeys.has(key)) continue;
    if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();
    state.layerBitmaps.delete(key);
  }
}

function clearBitmapCaches() {
  for (const decoded of state.layerBitmaps.values()) {
    if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();
  }
  state.layerBitmaps.clear();
  state.layerLoads.clear();
  for (const sample of state.samples.values()) {
    sample.canvas.width = 0;
    sample.canvas.height = 0;
  }
  state.samples.clear();
  state.sampleLoads.clear();
}

function screenToImage(clientX, clientY) {
  const rect = elements.viewer.getBoundingClientRect();
  return {
    x: (clientX - rect.left - state.offsetX) / state.zoom,
    y: (clientY - rect.top - state.offsetY) / state.zoom,
  };
}

function sampleFromEvent(event) {
  const image = screenToImage(event.clientX, event.clientY);
  if (image.x < 0 || image.y < 0 || image.x >= state.imageWidth || image.y >= state.imageHeight) return null;
  const scale = Number(state.manifest.scale || 1);
  const dataX = Math.floor(image.x * scale);
  const dataZ = Math.floor(image.y * scale);
  const world = state.manifest.world || {};
  const tileSize = Number(state.manifest.tile_size || 512);
  return {
    screenX: event.clientX,
    screenY: event.clientY,
    imageX: image.x,
    imageY: image.y,
    dataX,
    dataZ,
    minecraftX: Number(world.minecraft_min_x || 0) + dataX,
    minecraftZ: Number(world.minecraft_min_z || 0) + dataZ,
    tileX: Math.floor(dataX / tileSize),
    tileZ: Math.floor(dataZ / tileSize),
    localX: dataX % tileSize,
    localZ: dataZ % tileSize,
    height: samplePixel("height", image.x, image.y),
  };
}

function updateStatus(event) {
  const sample = sampleFromEvent(event);
  if (!sample) {
    setStatus("outside generated world");
    return;
  }
  const heightText = sample.height === null || sample.height === undefined ? "nodata/ocean" : `${(sample.height * 0.1).toFixed(1)} m`;
  const bng = bngText(sample.dataX, sample.dataZ);
  const details = layerDetails(sample);
  setStatus(`Minecraft x ${sample.minecraftX}, z ${sample.minecraftZ} | height ${heightText} | data ${sample.dataX},${sample.dataZ} | tile ${String(sample.tileX).padStart(3, "0")}_${String(sample.tileZ).padStart(3, "0")} cell ${sample.localX},${sample.localZ}${bng}${details}`);
}

function bngText(dataX, dataZ) {
  const geo = state.manifest.georeferencing || {};
  const world = state.manifest.world || {};
  if ([geo.bng_min_easting, geo.bng_max_easting, geo.bng_min_northing, geo.bng_max_northing].some((value) => value === undefined)) return "";
  const easting = Number(geo.bng_min_easting) + (dataX + 0.5) * (Number(geo.bng_max_easting) - Number(geo.bng_min_easting)) / Number(world.width);
  const northing = Number(geo.bng_max_northing) - (dataZ + 0.5) * (Number(geo.bng_max_northing) - Number(geo.bng_min_northing)) / Number(world.depth);
  return ` | BNG E ${easting.toFixed(0)}, N ${northing.toFixed(0)}`;
}

function layerDetails(sample) {
  const parts = [];
  for (const entry of state.layers.values()) {
    if (!entry.enabled || entry.layer.kind === "base") continue;
    const value = samplePixel(entry.layer.name, sample.dataX / Number(state.manifest.scale || 1), sample.dataZ / Number(state.manifest.scale || 1));
    if (value === null || value === undefined || value === 0) continue;
    if (entry.layer.kind === "ore") {
      parts.push(`${entry.layer.ore}: ${value}`);
    } else {
      parts.push(`${labelFor(entry.layer.name)}: ${classLabel(entry.layer.name, value)}`);
    }
  }
  return parts.length ? ` | ${parts.join(" | ")}` : "";
}

function classLabel(layerName, value) {
  const key = layerName === "surface" ? "surface_geology" : layerName;
  const classes = state.manifest[key]?.classes || {};
  return classes[String(value)]?.name || String(value);
}

function updateMeasurement(event) {
  const start = state.measureStart;
  const current = sampleFromEvent(event);
  if (!start || !current) {
    clearMeasurement();
    return;
  }
  if (Math.abs(current.screenX - start.screenX) + Math.abs(current.screenY - start.screenY) >= 4) state.measureMoved = true;
  if (!state.measureMoved) return;
  const line = ensureMeasurement();
  const dx = current.minecraftX - start.minecraftX;
  const dz = current.minecraftZ - start.minecraftZ;
  const distance = Math.hypot(dx, dz);
  const heightDelta = current.height !== null && current.height !== undefined && start.height !== null && start.height !== undefined
    ? `, dh ${((current.height - start.height) * 0.1).toFixed(1)} m`
    : "";
  const label = `${distance.toFixed(1)} blocks (${dx}, ${dz}${heightDelta})`;
  drawMeasurement(line, start.imageX, start.imageY, current.imageX, current.imageY, label);
}

function updateMeasurementOverlay() {
  if (!state.measure || !state.measureMoved || !state.measureStart) return;
  const line = state.measure.querySelector("line");
  if (!line) return;
  const x2 = Number(line.dataset.imageX2);
  const y2 = Number(line.dataset.imageY2);
  if (!Number.isFinite(x2) || !Number.isFinite(y2)) return;
  drawMeasurement(line, state.measureStart.imageX, state.measureStart.imageY, x2, y2, line.nextElementSibling.textContent);
}

function drawMeasurement(line, imageX1, imageY1, imageX2, imageY2, label) {
  line.dataset.imageX2 = String(imageX2);
  line.dataset.imageY2 = String(imageY2);
  const startPoint = imageToViewer(imageX1, imageY1);
  const endPoint = imageToViewer(imageX2, imageY2);
  line.setAttribute("x1", startPoint.x);
  line.setAttribute("y1", startPoint.y);
  line.setAttribute("x2", endPoint.x);
  line.setAttribute("y2", endPoint.y);
  line.nextElementSibling.setAttribute("x", (startPoint.x + endPoint.x) / 2);
  line.nextElementSibling.setAttribute("y", (startPoint.y + endPoint.y) / 2 - 8);
  line.nextElementSibling.textContent = label;
}

function ensureMeasurement() {
  if (state.measure) return state.measure.querySelector("line");
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.classList.add("measure-overlay");
  svg.setAttribute("width", "100%");
  svg.setAttribute("height", "100%");
  const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
  line.setAttribute("stroke", "#111111");
  line.setAttribute("stroke-width", "4");
  const text = document.createElementNS("http://www.w3.org/2000/svg", "text");
  text.setAttribute("fill", "#ffffff");
  text.setAttribute("stroke", "#111111");
  text.setAttribute("stroke-width", "4");
  text.setAttribute("paint-order", "stroke");
  text.setAttribute("text-anchor", "middle");
  text.setAttribute("font-size", "12");
  text.setAttribute("font-weight", "700");
  svg.append(line, text);
  elements.viewer.append(svg);
  state.measure = svg;
  return line;
}

function clearMeasurement() {
  if (state.measure) state.measure.remove();
  state.measure = null;
}

function imageToViewer(imageX, imageY) {
  return {
    x: state.offsetX + imageX * state.zoom,
    y: state.offsetY + imageY * state.zoom,
  };
}

async function loadSample(layer) {
  const sampleFile = layer.browser_sample_file || layer.sample_file;
  if (!sampleFile || state.samples.has(layer.name)) return;
  if (state.sampleLoads.has(layer.name)) return state.sampleLoads.get(layer.name);
  const load = (async () => {
    const response = await fetch(new URL(sampleFile, state.baseUrl), { cache: "force-cache" });
    if (!response.ok) return;
    const blob = await response.blob();
    const bitmap = await createImageBitmap(blob);
    const entry = state.layers.get(layer.name);
    if (layer.name !== "height" && (!entry || !entry.enabled)) {
      if (typeof bitmap.close === "function") bitmap.close();
      return;
    }
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    ctx.drawImage(bitmap, 0, 0);
    if (typeof bitmap.close === "function") bitmap.close();
    state.samples.set(layer.name, {
      canvas,
      ctx,
      width: canvas.width,
      height: canvas.height,
      heightLayer: layer.name === "height",
    });
  })().finally(() => state.sampleLoads.delete(layer.name));
  state.sampleLoads.set(layer.name, load);
  return load;
}

function releaseSample(layerName) {
  if (layerName === "height") return;
  const sample = state.samples.get(layerName);
  if (!sample) return;
  sample.canvas.width = 0;
  sample.canvas.height = 0;
  state.samples.delete(layerName);
}

function samplePixel(layerName, imageX, imageY) {
  const sample = state.samples.get(layerName);
  if (!sample) {
    const entry = state.layers.get(layerName);
    if (entry && layerName === "height") loadSample(entry.layer).catch(() => undefined);
    return undefined;
  }
  const x = Math.max(0, Math.min(sample.width - 1, Math.floor(imageX)));
  const y = Math.max(0, Math.min(sample.height - 1, Math.floor(imageY)));
  const rgba = sample.ctx.getImageData(x, y, 1, 1).data;
  if (!sample.heightLayer) return rgba[0];
  const encoded = rgba[0] + rgba[1] * 256;
  return encoded === 0 ? null : encoded - 32768;
}

async function copyCoordinates(event) {
  const sample = sampleFromEvent(event);
  if (!sample) return;
  const text = `${sample.minecraftX} ${sample.minecraftZ}`;
  try {
    await navigator.clipboard.writeText(text);
    setStatus(`Copied Minecraft coordinates: ${text}`);
  } catch {
    setStatus(`Minecraft coordinates: ${text}`);
  }
}

function setStatus(message) {
  elements.status.value = message;
}
