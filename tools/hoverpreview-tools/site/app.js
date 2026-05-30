const FORMAT = "ukgeo-hoverpreviews-v1";
const DEFAULT_MANIFEST = "hoverpreviews/hover_manifest.json";
const START_STATUS = "Mouse wheel zooms. Middle/right drag pans. Left click copies the current Minecraft coordinates.";

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
  minZoom: 0.02,
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
  samples: new Map(),
  measure: null,
};

elements.zoomIn.addEventListener("click", () => zoomAroundCentre(1.25));
elements.zoomOut.addEventListener("click", () => zoomAroundCentre(0.8));
elements.zoomFit.addEventListener("click", fitView);

elements.viewer.addEventListener("contextmenu", (event) => event.preventDefault());
elements.viewer.addEventListener("wheel", (event) => {
  event.preventDefault();
  zoomAt(event.clientX, event.clientY, event.deltaY < 0 ? 1.25 : 0.8);
}, { passive: false });

elements.viewer.addEventListener("pointerdown", (event) => {
  if (!state.manifest) return;
  elements.viewer.setPointerCapture(event.pointerId);
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
  if (state.panPointerId === event.pointerId) state.panPointerId = null;
  if (state.measurePointerId === event.pointerId) state.measurePointerId = null;
  elements.viewer.classList.remove("dragging");
});

elements.viewer.addEventListener("pointerleave", () => {
  if (!state.manifest) return;
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
  return document.body.dataset.defaultManifest || DEFAULT_MANIFEST;
}

async function loadManifest(url) {
  if (elements.loadState) elements.loadState.textContent = `Loading ${url}…`;
  setStatus(`Loading ${url}…`);
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
  state.samples.clear();
  clearMeasurement();
  elements.stack.replaceChildren();
  elements.layerControls.replaceChildren();
  elements.oreControls.replaceChildren();

  for (const layer of manifest.layers || []) {
    addLayer(layer);
    if (layer.browser_sample_file || layer.sample_file) loadSample(layer).catch(() => undefined);
  }

  elements.empty.hidden = true;
  elements.stack.style.width = `${state.imageWidth}px`;
  elements.stack.style.height = `${state.imageHeight}px`;
  fitView();
  if (elements.loadState) elements.loadState.textContent = `Loaded ${(manifest.layers || []).length} layers`;
  setStatus(START_STATUS);
}

function addLayer(layer) {
  const img = document.createElement("img");
  img.className = "map-layer";
  img.alt = "";
  img.draggable = false;
  img.width = state.imageWidth;
  img.height = state.imageHeight;
  img.dataset.name = layer.name;
  img.src = layerUrl(layer, chooseMip(layer));
  img.hidden = layer.kind !== "base" && !["surface", "vegetation", "rivers"].includes(layer.name);
  elements.stack.append(img);

  state.layers.set(layer.name, { layer, img, enabled: !img.hidden });
  const controls = layer.kind === "ore" ? elements.oreControls : elements.layerControls;
  controls.append(toggleFor(layer, !img.hidden));
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
    entry.img.hidden = !input.checked;
    updateAllLayerSources();
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
  const mips = layer.mips || [{ factor: 1, file: layer.file }];
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

function updateAllLayerSources() {
  for (const entry of state.layers.values()) {
    if (!entry.enabled) continue;
    const url = layerUrl(entry.layer, chooseMip(entry.layer));
    if (entry.img.src !== url) entry.img.src = url;
  }
}

function fitView() {
  if (!state.manifest) return;
  const rect = elements.viewer.getBoundingClientRect();
  state.zoom = Math.max(state.minZoom, Math.min(1, rect.width / state.imageWidth, rect.height / state.imageHeight));
  state.offsetX = 0;
  state.offsetY = 0;
  applyTransform();
}

function zoomAroundCentre(factor) {
  const rect = elements.viewer.getBoundingClientRect();
  zoomAt(rect.left + rect.width / 2, rect.top + rect.height / 2, factor);
}

function zoomAt(clientX, clientY, factor) {
  if (!state.manifest) return;
  const rect = elements.viewer.getBoundingClientRect();
  const before = screenToImage(clientX, clientY);
  state.zoom = Math.max(state.minZoom, Math.min(64, state.zoom * factor));
  state.offsetX = clientX - rect.left - before.x * state.zoom;
  state.offsetY = clientY - rect.top - before.y * state.zoom;
  applyTransform();
}

function applyTransform() {
  const rect = elements.viewer.getBoundingClientRect();
  const scaledWidth = state.imageWidth * state.zoom;
  const scaledHeight = state.imageHeight * state.zoom;
  const minX = Math.min(0, rect.width - scaledWidth);
  const minY = Math.min(0, rect.height - scaledHeight);
  state.offsetX = Math.min(0, Math.max(minX, state.offsetX));
  state.offsetY = Math.min(0, Math.max(minY, state.offsetY));
  elements.stack.style.transform = `translate(${state.offsetX}px, ${state.offsetY}px) scale(${state.zoom})`;
  elements.zoomLabel.textContent = `${Math.round(state.zoom * 100)}%`;
  updateScrollbars(rect, scaledWidth, scaledHeight);
  updateAllLayerSources();
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
    ? `, Δh ${((current.height - start.height) * 0.1).toFixed(1)} m`
    : "";
  const label = `${distance.toFixed(1)} blocks (${dx}, ${dz}${heightDelta})`;
  const startPoint = imageToViewer(start.imageX, start.imageY);
  const endPoint = imageToViewer(current.imageX, current.imageY);
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
  const response = await fetch(new URL(sampleFile, state.baseUrl), { cache: "force-cache" });
  if (!response.ok) return;
  const blob = await response.blob();
  const bitmap = await createImageBitmap(blob);
  const canvas = document.createElement("canvas");
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  ctx.drawImage(bitmap, 0, 0);
  state.samples.set(layer.name, {
    ctx,
    width: canvas.width,
    height: canvas.height,
    heightLayer: layer.name === "height",
  });
}

function samplePixel(layerName, imageX, imageY) {
  const sample = state.samples.get(layerName);
  if (!sample) return undefined;
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
