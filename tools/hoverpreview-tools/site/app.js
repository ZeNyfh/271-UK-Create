const FORMAT = "ukgeo-hoverpreviews-v1";
const DEFAULT_MANIFEST = "hoverpreviews/hover_manifest.json";
const DEFAULT_ANIMALS_LIST = "animals.txt";
const DEFAULT_ANIMALS_TEXT = `
# Built-in fallback used when animals.txt cannot be fetched.
0|none|unknown: —
1|broadleaf_woodland|broadleaf woodland|forest|minecraft:forest|ukgeo:broadleaf_woodland: Deer, Boar, Squirrel, Owl, Fox, Pig
2|conifer_woodland|conifer woodland|taiga|minecraft:taiga|ukgeo:conifer_woodland: Deer, Squirrel, Owl, Wolf
3|arable|arable_and_horticulture|arable and horticulture|farmland|plains|minecraft:plains|ukgeo:arable: Cow, Chicken, Pig, Hedgehog, Fox
4|improved_grassland|improved grassland|pasture|ukgeo:improved_grassland: Cow, Sheep, Chicken, Deer
5|neutral_grassland|neutral grassland|meadow|grassland|minecraft:meadow|ukgeo:neutral_grassland: Cow, Sheep, Rabbit, Deer, Minisheep
6|calcareous_grassland|calcareous grassland|ukgeo:calcareous_grassland: Sheep, Rabbit, Deer, Minisheep
7|acid_grassland|acid grassland|upland grassland|ukgeo:acid_grassland: Sheep, Rabbit, Deer, Minisheep
8|wetland|wetland_bog_fen|wetland/bog/fen|swamp|minecraft:swamp|ukgeo:wetland: Owl, Bat
9|heath|heath_heather|heath/heather|moorland|heathland|ukgeo:heath: Rabbit, Fox, Deer, Boar, Owl, Bison
10|freshwater|river|lake|minecraft:river|ukgeo:freshwater: —
11|urban|urban_suburban|urban/suburban|settlement|ukgeo:urban: Dog, Hedgehog, Chicken, Fox
12|rocky|upland|mountain|stony_peaks|minecraft:stony_peaks|ukgeo:rocky: Sheep, Rabbit, Bat
13|coastal_ocean|ocean|sea|beach|coast|minecraft:ocean|ukgeo:coastal_ocean: —
`;
const START_STATUS = "Mouse wheel zooms. Middle/right drag pans. Left drag measures distance. Left click copies the current Minecraft coordinates.";
const DEFAULT_VISIBLE_OVERLAYS = new Set(["surface", "vegetation", "rivers"]);
const DEFAULT_VISIBLE_ORES = new Set(["coal", "iron", "copper", "zinc", "gold"]);
const MAX_DEVICE_PIXEL_RATIO = 2;
const DECODE_CHUNK_PIXELS = 192;
const MAX_TILE_BITMAPS = 512;
const MAX_SAMPLE_TILES = 192;
const MAX_CONCURRENT_BITMAP_LOADS = 10;
const CHUNK_LOAD_DEBOUNCE_MS = 60;
const DEFAULT_TILE_SIZE = 256;
const SAMPLE_LOAD_PRIORITY = -10000;
const ABSOLUTE_MIN_MAP_ZOOM = 0.01;
const MIN_ZOOM_FROM_FIT_FACTOR = 0.7;
const MAX_DISPLAY_ZOOM_PERCENT = 500;
const WHEEL_DELTA_PER_ZOOM_STEP = 100;
const PINCH_ZOOM_SENSITIVITY = 1.25;
const PINCH_ZOOM_DEADZONE = 0.2;
const PINCH_MIN_DISTANCE = 8;
const SAMPLE_CROP_SIZE = 512;
const DEFAULT_RENDERER_PREFERENCE = "auto";
const FIT_VIEW_PADDING_PX = 72;
const LIVE_WEATHER_REFRESH_MS = 15 * 60 * 1000;
const LIVE_WEATHER_GRID_COLUMNS = 12;
const RenderMath = globalThis.HoverRenderMath || {};
const BACKGROUND_ORE_ATTEMPT_MULTIPLIER = 0.1;
const ORE_AREA_ATTEMPT_MULTIPLIER = 3.0;
// Hover UI displays ore density in player-facing relative density units;
// the raw attempt ratio is 10x larger than the intended displayed multiplier.
const ORE_DISPLAY_MULTIPLIER_DIVISOR = 10.0;
const ORE_ATTEMPT_SETTINGS = {
  coal: { base: 1, maxBonus: 14 },
  iron: { base: 1, maxBonus: 10 },
  copper: { base: 1, maxBonus: 10 },
  zinc: { base: 1, maxBonus: 14 },
  gold: { base: 0, maxBonus: 5 },
  andesite: { base: 0, maxBonus: 18 },
  diorite: { base: 0, maxBonus: 14 },
  granite: { base: 0, maxBonus: 18 },
  ochrum: { base: 0, maxBonus: 14 },
  calcite: { base: 0, maxBonus: 14 },
  scoria: { base: 0, maxBonus: 14 },
  tuff: { base: 0, maxBonus: 16 },
  crimsite: { base: 0, maxBonus: 14 },
  limestone: { base: 0, maxBonus: 16 },
  asurine: { base: 0, maxBonus: 12 },
  veridium: { base: 0, maxBonus: 12 },
  smooth_basalt: { base: 0, maxBonus: 16 },
};

const elements = {
  loadState: document.querySelector("#load-state"),
  controls: document.querySelector(".controls"),
  layerControls: document.querySelector("#layer-controls"),
  oreControls: document.querySelector("#ore-controls"),
  animalControls: document.querySelector("#animal-controls"),
  viewer: document.querySelector("#viewer"),
  stack: document.querySelector("#map-stack"),
  empty: document.querySelector("#empty-state"),
  status: document.querySelector("#status"),
  zoomIn: document.querySelector("#zoom-in"),
  zoomOut: document.querySelector("#zoom-out"),
  zoomFit: document.querySelector("#zoom-fit"),
  zoomLabel: document.querySelector("#zoom-label"),
  latlonForm: document.querySelector("#latlon-form"),
  latInput: document.querySelector("#lat-input"),
  lonInput: document.querySelector("#lon-input"),
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
  sourceBlobs: new Map(),
  activeLayerBitmapKeys: new Set(),
  bitmapLoadQueue: [],
  activeBitmapLoads: 0,
  pendingLayerLoadRequests: new Map(),
  chunkLoadRequestTimer: 0,
  samples: new Map(),
  sampleLoads: new Map(),
  sampleTiles: new Map(),
  sampleTileLoads: new Map(),
  sampleGeneration: 0,
  animals: new Map(),
  animalsLoaded: false,
  animalsLoadError: null,
  measure: null,
  mapCanvas: null,
  mapRenderer: null,
  mapRendererMode: "2d",
  mapRendererFallbackReason: "",
  mapCtx: null,
  layerTextures: new Map(),
  renderRequest: 0,
  chunkRenderRequest: 0,
  wheelZoomDelta: 0,
  wheelZoomPrecise: false,
  touchPointers: new Map(),
  pinchDistance: null,
  pinchCenterX: null,
  pinchCenterY: null,
  pinchStartZoom: null,
  pinchStartImageX: null,
  pinchStartImageY: null,
  lastStatusPoint: null,
  liveWeather: {
    timer: 0,
    loading: false,
    cloud_cover: null,
    downfall_coverage: null,
  },
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
elements.latlonForm.addEventListener("submit", (event) => {
  event.preventDefault();
  goToLatLon();
});

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
    updateMeasurement(event);
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
  if (state.touchPointers.size > 0 || state.panPointerId !== null || state.measurePointerId !== null) return;
  state.lastStatusPoint = null;
  setStatus(START_STATUS);
});

window.addEventListener("resize", () => {
  if (state.manifest) applyTransform();
});

loadManifest(defaultManifest()).catch((error) => {
  if (elements.loadState) elements.loadState.textContent = "Preview data not found";
  if (elements.empty) elements.empty.hidden = false;
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
  state.animals.clear();
  state.animalsLoaded = false;
  state.animalsLoadError = null;
  resetLiveWeatherState();
  clearBitmapCaches();
  destroyMapRenderer();
  clearMeasurement();
  elements.stack.replaceChildren();
  elements.layerControls.replaceChildren();
  elements.oreControls.replaceChildren();
  elements.animalControls?.replaceChildren();

  state.mapCanvas = document.createElement("canvas");
  state.mapCanvas.className = "map-canvas";
  elements.stack.append(state.mapCanvas);
  setupMapRenderer(state.mapCanvas, manifest);

  const liveWeatherConfig = resolveLiveWeatherConfig(manifest);
  if (liveWeatherConfig) manifest.live_weather = liveWeatherConfig;

  for (const layer of manifest.layers || []) {
    addLayer(layer);
  }
  installLiveWeatherLayers(manifest);
  if (manifest.live_weather) {
    state.mapRendererFallbackReason = "live-weather-2d";
    destroyMapRenderer({ keepCanvas: true });
    createCanvasRenderer(state.mapCanvas);
  }

  loadAnimalsList(manifest).catch(() => undefined);

  elements.empty.hidden = true;
  fitView();
  if (elements.loadState) {
    const staticCount = (manifest.layers || []).length;
    const liveCount = manifest.live_weather ? 2 : 0;
    const suffix = state.mapRendererMode === "webgl" ? " · WebGL" : state.mapRendererFallbackReason ? " · 2D fallback" : " · 2D";
    elements.loadState.textContent = `Loaded ${staticCount + liveCount} layers${suffix}`;
  }
  setStatus(START_STATUS);
}

function rendererPreferenceForManifest(manifest) {
  const queryRenderer = new URLSearchParams(location.search).get("renderer");
  return normalizeRendererPreference(queryRenderer || manifest?.viewer?.renderer_preference || DEFAULT_RENDERER_PREFERENCE);
}

function normalizeRendererPreference(value) {
  const text = String(value || DEFAULT_RENDERER_PREFERENCE).trim().toLowerCase();
  if (text === "canvas") return "2d";
  if (text === "webgl" || text === "2d") return text;
  return DEFAULT_RENDERER_PREFERENCE;
}

function setupMapRenderer(canvas, manifest) {
  state.mapRendererFallbackReason = "";
  const preference = rendererPreferenceForManifest(manifest);
  if (preference !== "2d") {
    try {
      const renderer = createWebGlRenderer(canvas);
      if (renderer) {
        state.mapRenderer = renderer;
        state.mapRendererMode = "webgl";
        state.mapCtx = null;
        return;
      }
      state.mapRendererFallbackReason = "webgl-unavailable";
    } catch (error) {
      console.warn("[hoverpreview] WebGL renderer setup failed, falling back to 2D", error);
      state.mapRendererFallbackReason = error?.message || "webgl-unavailable";
    }
  }
  createCanvasRenderer(canvas);
}

function createCanvasRenderer(canvas) {
  const ctx = canvas.getContext("2d", { alpha: false });
  if (!ctx) throw new Error("2D canvas context unavailable");
  ctx.imageSmoothingEnabled = true;
  state.mapRenderer = { mode: "2d", canvas, ctx };
  state.mapRendererMode = "2d";
  state.mapCtx = ctx;
}

function createWebGlRenderer(canvas) {
  const options = { alpha: false, antialias: true, premultipliedAlpha: false, preserveDrawingBuffer: false };
  const gl = canvas.getContext("webgl2", options) || canvas.getContext("webgl", options) || canvas.getContext("experimental-webgl", options);
  if (!gl) return null;

  const vertexShader = compileShader(gl, gl.VERTEX_SHADER, `
    attribute vec2 a_position;
    attribute vec2 a_texCoord;
    uniform vec2 u_resolution;
    varying vec2 v_texCoord;
    void main() {
      vec2 zeroToOne = a_position / u_resolution;
      vec2 clipSpace = zeroToOne * 2.0 - 1.0;
      gl_Position = vec4(clipSpace * vec2(1.0, -1.0), 0.0, 1.0);
      v_texCoord = a_texCoord;
    }
  `);
  const fragmentShader = compileShader(gl, gl.FRAGMENT_SHADER, `
    precision mediump float;
    uniform sampler2D u_texture;
    varying vec2 v_texCoord;
    void main() {
      gl_FragColor = texture2D(u_texture, v_texCoord);
    }
  `);
  const program = createShaderProgram(gl, vertexShader, fragmentShader);
  const buffer = gl.createBuffer();
  if (!buffer) throw new Error("Failed to create WebGL buffer");

  gl.useProgram(program);
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
  gl.disable(gl.DEPTH_TEST);
  gl.disable(gl.DITHER);
  gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
  if ("UNPACK_COLORSPACE_CONVERSION_WEBGL" in gl) {
    gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, gl.NONE);
  }

  return {
    mode: "webgl",
    canvas,
    gl,
    program,
    vertexShader,
    fragmentShader,
    buffer,
    positionLocation: gl.getAttribLocation(program, "a_position"),
    texCoordLocation: gl.getAttribLocation(program, "a_texCoord"),
    resolutionLocation: gl.getUniformLocation(program, "u_resolution"),
    textureLocation: gl.getUniformLocation(program, "u_texture"),
  };
}

function compileShader(gl, type, source) {
  const shader = gl.createShader(type);
  if (!shader) throw new Error("Failed to create WebGL shader");
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const message = gl.getShaderInfoLog(shader) || "unknown shader error";
    gl.deleteShader(shader);
    throw new Error(message);
  }
  return shader;
}

function createShaderProgram(gl, vertexShader, fragmentShader) {
  const program = gl.createProgram();
  if (!program) throw new Error("Failed to create WebGL program");
  gl.attachShader(program, vertexShader);
  gl.attachShader(program, fragmentShader);
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const message = gl.getProgramInfoLog(program) || "unknown program link error";
    gl.deleteProgram(program);
    throw new Error(message);
  }
  return program;
}

function destroyMapRenderer({ keepCanvas = false } = {}) {
  releaseAllLayerTextures();
  const renderer = state.mapRenderer;
  if (renderer?.mode === "webgl" && renderer.gl) {
    const gl = renderer.gl;
    if (renderer.buffer) gl.deleteBuffer(renderer.buffer);
    if (renderer.program) gl.deleteProgram(renderer.program);
    if (renderer.vertexShader) gl.deleteShader(renderer.vertexShader);
    if (renderer.fragmentShader) gl.deleteShader(renderer.fragmentShader);
  }
  state.mapRenderer = null;
  state.mapRendererMode = "2d";
  state.mapCtx = null;
  if (!keepCanvas) state.mapCanvas = null;
}

function fallbackToCanvasRenderer(error) {
  if (!state.mapCanvas) return;
  console.warn("[hoverpreview] Falling back to 2D renderer", error);
  state.mapRendererFallbackReason = error?.message || String(error || "webgl-runtime-failure");
  destroyMapRenderer({ keepCanvas: true });
  createCanvasRenderer(state.mapCanvas);
  if (elements.loadState && state.manifest) {
    elements.loadState.textContent = `Loaded ${(state.manifest.layers || []).length} layers · 2D fallback`;
  }
}

function addLayer(layer) {
  const enabled = isLayerVisibleByDefault(layer);
  state.layers.set(layer.name, { layer, enabled });
  if (layer.name === "biome_regions") return;
  const controls = layer.kind === "ore"
    ? elements.oreControls
    : layer.kind === "animal"
      ? elements.animalControls
      : elements.layerControls;
  if (!controls) {
    console.warn("[hoverpreview] Missing layer control container for layer", layer.name, layer.kind);
    return;
  }
  controls.append(toggleFor(layer, enabled));
}

function installLiveWeatherLayers(manifest) {
  if (!manifest?.live_weather?.grid?.latitudes?.length) return;
  addLayer({
    name: "cloud_cover",
    kind: "weather-live",
    label: "Cloud coverage",
    value_format: "percent",
    live_weather_metric: "cloud_cover",
  });
  addLayer({
    name: "downfall_coverage",
    kind: "weather-live",
    label: "Rain / precipitation",
    value_format: "percent",
    live_weather_metric: "downfall_coverage",
  });
}

function resolveLiveWeatherConfig(manifest) {
  const configured = manifest?.live_weather;
  if (configured?.grid?.latitudes?.length && configured?.grid?.longitudes?.length) return configured;

  const grid = buildLiveWeatherGrid(manifest, LIVE_WEATHER_GRID_COLUMNS);
  if (!grid) return null;
  return {
    provider: "Open-Meteo",
    api_base_url: "https://api.open-meteo.com/v1/forecast",
    weather_model: "auto",
    batch_points: 64,
    metrics: {
      cloud_cover: { unit: "percent", source: "current.cloud_cover" },
      downfall_coverage: { unit: "percent", source: "hourly.precipitation_probability[0]" },
    },
    grid,
  };
}

function buildLiveWeatherGrid(manifest, requestedColumns) {
  const world = manifest?.world || {};
  const geo = manifest?.georeferencing || {};
  const values = [world.width, world.depth, geo.bng_min_easting, geo.bng_max_easting, geo.bng_min_northing, geo.bng_max_northing];
  if (values.some((value) => !Number.isFinite(Number(value)))) return null;
  if (geo.crs && String(geo.crs).toUpperCase() !== "EPSG:27700") return null;

  const width = Number(world.width);
  const depth = Number(world.depth);
  if (width <= 0 || depth <= 0) return null;
  const columns = Math.max(2, Math.round(Number(requestedColumns) || LIVE_WEATHER_GRID_COLUMNS));
  const rows = Math.max(2, Math.round(columns * depth / width));
  const latitudes = [];
  const longitudes = [];
  for (let row = 0; row < rows; row += 1) {
    const dataZ = rows === 1 ? 0.5 : 0.5 + row * (depth - 1) / (rows - 1);
    const northing = Number(geo.bng_max_northing) - dataZ * (Number(geo.bng_max_northing) - Number(geo.bng_min_northing)) / Math.max(1, depth);
    for (let column = 0; column < columns; column += 1) {
      const dataX = columns === 1 ? 0.5 : 0.5 + column * (width - 1) / (columns - 1);
      const easting = Number(geo.bng_min_easting) + dataX * (Number(geo.bng_max_easting) - Number(geo.bng_min_easting)) / Math.max(1, width);
      const point = britishNationalGridToWgs84(easting, northing);
      latitudes.push(point.lat);
      longitudes.push(point.lon);
    }
  }
  return { rows, columns, latitudes, longitudes };
}

function isLayerVisibleByDefault(layer) {
  if (layer.kind === "base") return true;
  if (layer.kind === "ore") return DEFAULT_VISIBLE_ORES.has(layer.ore || labelFor(layer.name));
  if (layer.kind === "animal") return false;
  if (layer.kind === "weather-live") return false;
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
    if (input.checked && layer.kind === "weather-live" && !state.liveWeather[layer.live_weather_metric]) {
      fetchLiveWeather(state.manifest).catch((error) => {
        console.warn("[hoverpreview] Live weather fetch failed", error);
        refreshStatus();
      });
    }
    if (!input.checked) {
      releaseLayerBitmaps(layer.name);
      releaseSample(layer.name);
    }
    scheduleRender();
    refreshStatus();
  });
  const name = document.createElement("span");
  name.textContent = layer.label || layer.ore || labelFor(layer.name);
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
  return assetUrl(mip.file || layer.file);
}

function layerCacheKey(layer, mip) {
  return `${layer.name}\n${Number(mip.factor) || 1}\n${mip.file || layer.file}`;
}

function layerRegionCacheKey(layer, mip, region) {
  return `${layerCacheKey(layer, mip)}\n${region.left},${region.top},${region.right},${region.bottom}`;
}

function layerRegionUrl(layer, mip, region) {
  if (mip.tiles && region.tileX !== undefined && region.tileY !== undefined) {
    return assetUrl(
      String(mip.tiles.template).replace("{x}", String(region.tileX)).replace("{y}", String(region.tileY)),
    );
  }
  return layerUrl(layer, mip);
}

function assetUrl(path) {
  const url = new URL(path, state.baseUrl || location.href);
  const cacheBuster = String(state.manifest?.generation?.cache_buster || "").trim();
  if (cacheBuster) url.searchParams.set("v", cacheBuster);
  return url.href;
}

function fitView() {
  if (!state.manifest) return;
  const rect = elements.viewer.getBoundingClientRect();
  const bounds = viewerFitBounds();
  const boundsWidth = Math.max(1, bounds.right - bounds.left);
  const boundsHeight = Math.max(1, bounds.bottom - bounds.top);
  state.zoom = clampZoom(fitZoomToViewport(rect.width, rect.height, boundsWidth, boundsHeight));
  state.offsetX = rect.width / 2 - ((bounds.left + bounds.right) / 2) * state.zoom;
  state.offsetY = rect.height / 2 - ((bounds.top + bounds.bottom) / 2) * state.zoom;
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
  if (!gesture || gesture.distance < PINCH_MIN_DISTANCE) return;

  const rect = elements.viewer.getBoundingClientRect();

  state.pinchDistance = gesture.distance;
  state.pinchCenterX = gesture.centerX;
  state.pinchCenterY = gesture.centerY;

  state.pinchStartZoom = state.zoom;
  state.pinchStartImageX = (gesture.centerX - rect.left - state.offsetX) / state.zoom;
  state.pinchStartImageY = (gesture.centerY - rect.top - state.offsetY) / state.zoom;
}

function updatePinchGesture() {
  const gesture = pinchGesture();
  if (!gesture) return;

  if (state.pinchDistance === null || state.pinchStartZoom === null) {
    beginPinch();
    return;
  }

  const rect = elements.viewer.getBoundingClientRect();

  const rawRatio = gesture.distance / Math.max(PINCH_MIN_DISTANCE, state.pinchDistance);

  const distanceFromNeutral = Math.abs(rawRatio - 1);
  const direction = rawRatio >= 1 ? 1 : -1;

  let effectiveRatio = 1;

  if (distanceFromNeutral > PINCH_ZOOM_DEADZONE) {
    const adjustedDistance = distanceFromNeutral - PINCH_ZOOM_DEADZONE;
    effectiveRatio = 1 + direction * adjustedDistance;
  }

  const zoomRatio = Math.pow(effectiveRatio, PINCH_ZOOM_SENSITIVITY);

  state.zoom = clampZoom(state.pinchStartZoom * zoomRatio);

  state.offsetX = gesture.centerX - rect.left - state.pinchStartImageX * state.zoom;
  state.offsetY = gesture.centerY - rect.top - state.pinchStartImageY * state.zoom;

  state.pinchCenterX = gesture.centerX;
  state.pinchCenterY = gesture.centerY;

  applyTransform();
}

function resetPinch() {
  state.pinchDistance = null;
  state.pinchCenterX = null;
  state.pinchCenterY = null;
  state.pinchStartZoom = null;
  state.pinchStartImageX = null;
  state.pinchStartImageY = null;
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
  return Math.max(minimumMapZoom(), Math.min(maximumMapZoom(), zoom));
}

function minimumMapZoom() {
  if (!state.manifest || !state.imageWidth || !state.imageHeight) return ABSOLUTE_MIN_MAP_ZOOM;
  const rect = elements.viewer.getBoundingClientRect();
  const bounds = viewerFitBounds();
  const boundsWidth = Math.max(1, bounds.right - bounds.left);
  const boundsHeight = Math.max(1, bounds.bottom - bounds.top);
  const fitZoom = fitZoomToViewport(rect.width, rect.height, boundsWidth, boundsHeight);
  return Math.max(ABSOLUTE_MIN_MAP_ZOOM, fitZoom * MIN_ZOOM_FROM_FIT_FACTOR);
}

function maximumMapZoom() {
  return minimumMapZoom() + MAX_DISPLAY_ZOOM_PERCENT / 100;
}

function fitZoomToViewport(viewportWidth, viewportHeight, imageWidth, imageHeight) {
  if (typeof RenderMath.fitZoom === "function") {
    return RenderMath.fitZoom(viewportWidth, viewportHeight, imageWidth, imageHeight, FIT_VIEW_PADDING_PX);
  }
  return Math.min(1, Math.max(1, viewportWidth - FIT_VIEW_PADDING_PX * 2) / imageWidth, Math.max(1, viewportHeight - FIT_VIEW_PADDING_PX * 2) / imageHeight);
}

function clampAxisOffset(offset, viewportSize, scaledImageSize) {
  if (typeof RenderMath.clampAxisOffset === "function") {
    return RenderMath.clampAxisOffset(offset, viewportSize, scaledImageSize);
  }
  const minOffset = Math.min(0, viewportSize - scaledImageSize);
  const maxOffset = Math.max(0, viewportSize - scaledImageSize);
  return Math.min(maxOffset, Math.max(minOffset, offset));
}

function viewerFitBounds() {
  return { left: 0, top: 0, right: state.imageWidth, bottom: state.imageHeight };
}

function heightDataBounds() {
  const bounds = state.manifest?.content_bounds?.height;
  if (!bounds) return null;
  const left = Math.max(0, Math.min(state.imageWidth, Number(bounds.left) || 0));
  const top = Math.max(0, Math.min(state.imageHeight, Number(bounds.top) || 0));
  const right = Math.max(left + 1, Math.min(state.imageWidth, Number(bounds.right) || state.imageWidth));
  const bottom = Math.max(top + 1, Math.min(state.imageHeight, Number(bounds.bottom) || state.imageHeight));
  return { left, top, right, bottom };
}

function isInsideHeightDataBounds(imageX, imageY) {
  const bounds = heightDataBounds();
  if (!bounds) return true;
  return imageX >= bounds.left && imageY >= bounds.top && imageX < bounds.right && imageY < bounds.bottom;
}

function displayZoomPercent() {
  const minZoom = minimumMapZoom();
  return Math.max(0, Math.min(MAX_DISPLAY_ZOOM_PERCENT, Math.round((state.zoom - minZoom) * 100)));
}

function zoomForDisplayPercent(percent) {
  const cleanPercent = Number.isFinite(percent) ? percent : displayZoomPercent();
  return clampZoom(minimumMapZoom() + Math.max(0, Math.min(MAX_DISPLAY_ZOOM_PERCENT, cleanPercent)) / 100);
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

function goToLatLon() {
  if (!state.manifest) return;
  const lon = Number.parseFloat(elements.lonInput.value);
  const lat = Number.parseFloat(elements.latInput.value);
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) {
    setStatus("Enter longitude and latitude as decimal degrees.");
    return;
  }
  const bng = wgs84ToBritishNationalGrid(lat, lon);
  const image = bngToImage(bng.easting, bng.northing);
  if (!image) {
    setStatus("This preview does not include BNG georeferencing.");
    return;
  }
  centerImagePoint(image.x, image.y);
  const point = statusPointFromImage(image.x, image.y);
  setStatus(`Centered on lon ${lon.toFixed(6)}, lat ${lat.toFixed(6)} | BNG E ${bng.easting.toFixed(0)}, N ${bng.northing.toFixed(0)} | Minecraft x ${point.minecraftX}, z ${point.minecraftZ}`);
}

function centerImagePoint(imageX, imageY) {
  const rect = elements.viewer.getBoundingClientRect();
  state.offsetX = rect.width / 2 - imageX * state.zoom;
  state.offsetY = rect.height / 2 - imageY * state.zoom;
  applyTransform();
}

function bngToImage(easting, northing) {
  const geo = state.manifest.georeferencing || {};
  const world = state.manifest.world || {};
  const required = [geo.bng_min_easting, geo.bng_max_easting, geo.bng_min_northing, geo.bng_max_northing, world.width, world.depth];
  if (required.some((value) => value === undefined)) return null;
  const dataX = (easting - Number(geo.bng_min_easting)) * Number(world.width) / (Number(geo.bng_max_easting) - Number(geo.bng_min_easting)) - 0.5;
  const dataZ = (Number(geo.bng_max_northing) - northing) * Number(world.depth) / (Number(geo.bng_max_northing) - Number(geo.bng_min_northing)) - 0.5;
  const scale = Number(state.manifest.scale || 1);
  return { x: dataX / scale, y: dataZ / scale };
}

function wgs84ToBritishNationalGrid(latDeg, lonDeg) {
  const wgs84 = latLonToCartesian(latDeg, lonDeg, 6378137.0, 6356752.3141);
  const osgb36 = helmertTransformWgs84ToOsgb36(wgs84);
  const latLon = cartesianToLatLon(osgb36.x, osgb36.y, osgb36.z, 6377563.396, 6356256.909);
  return osgb36LatLonToBng(latLon.lat, latLon.lon);
}

function britishNationalGridToWgs84(easting, northing) {
  const osgb36LatLon = bngToOsgb36LatLon(easting, northing);
  const osgb36 = latLonToCartesian(
    radiansToDegrees(osgb36LatLon.lat),
    radiansToDegrees(osgb36LatLon.lon),
    6377563.396,
    6356256.909,
  );
  const wgs84 = helmertTransformOsgb36ToWgs84(osgb36);
  const latLon = cartesianToLatLon(wgs84.x, wgs84.y, wgs84.z, 6378137.0, 6356752.3141);
  return { lat: radiansToDegrees(latLon.lat), lon: radiansToDegrees(latLon.lon) };
}

function bngToOsgb36LatLon(easting, northing) {
  const a = 6377563.396;
  const b = 6356256.909;
  const f0 = 0.9996012717;
  const lat0 = degreesToRadians(49);
  const lon0 = degreesToRadians(-2);
  const n0 = -100000;
  const e0 = 400000;
  const e2 = 1 - (b * b) / (a * a);
  const n = (a - b) / (a + b);
  let lat = lat0;
  let meridionalArc = 0;
  do {
    lat = (Number(northing) - n0 - meridionalArc) / (a * f0) + lat;
    meridionalArc = b * f0 * (
      (1 + n + 1.25 * n ** 2 + 1.25 * n ** 3) * (lat - lat0)
      - (3 * n + 3 * n ** 2 + 2.625 * n ** 3) * Math.sin(lat - lat0) * Math.cos(lat + lat0)
      + (1.875 * n ** 2 + 1.875 * n ** 3) * Math.sin(2 * (lat - lat0)) * Math.cos(2 * (lat + lat0))
      - (35 / 24) * n ** 3 * Math.sin(3 * (lat - lat0)) * Math.cos(3 * (lat + lat0))
    );
  } while (Math.abs(Number(northing) - n0 - meridionalArc) >= 0.00001);

  const sinLat = Math.sin(lat);
  const cosLat = Math.cos(lat);
  const tanLat = Math.tan(lat);
  const nu = a * f0 / Math.sqrt(1 - e2 * sinLat ** 2);
  const rho = a * f0 * (1 - e2) / ((1 - e2 * sinLat ** 2) ** 1.5);
  const eta2 = nu / rho - 1;
  const dE = Number(easting) - e0;
  const vii = tanLat / (2 * rho * nu);
  const viii = tanLat / (24 * rho * nu ** 3) * (5 + 3 * tanLat ** 2 + eta2 - 9 * tanLat ** 2 * eta2);
  const ix = tanLat / (720 * rho * nu ** 5) * (61 + 90 * tanLat ** 2 + 45 * tanLat ** 4);
  const x = 1 / (cosLat * nu);
  const xi = 1 / (6 * cosLat * nu ** 3) * (nu / rho + 2 * tanLat ** 2);
  const xii = 1 / (120 * cosLat * nu ** 5) * (5 + 28 * tanLat ** 2 + 24 * tanLat ** 4);
  const xiia = 1 / (5040 * cosLat * nu ** 7) * (61 + 662 * tanLat ** 2 + 1320 * tanLat ** 4 + 720 * tanLat ** 6);
  return {
    lat: lat - vii * dE ** 2 + viii * dE ** 4 - ix * dE ** 6,
    lon: lon0 + x * dE - xi * dE ** 3 + xii * dE ** 5 - xiia * dE ** 7,
  };
}

function latLonToCartesian(latDeg, lonDeg, a, b) {
  const lat = degreesToRadians(latDeg);
  const lon = degreesToRadians(lonDeg);
  const e2 = 1 - (b * b) / (a * a);
  const nu = a / Math.sqrt(1 - e2 * Math.sin(lat) ** 2);
  return {
    x: nu * Math.cos(lat) * Math.cos(lon),
    y: nu * Math.cos(lat) * Math.sin(lon),
    z: (nu * (1 - e2)) * Math.sin(lat),
  };
}

function helmertTransformWgs84ToOsgb36(point) {
  const tx = -446.448;
  const ty = 125.157;
  const tz = -542.060;
  const scale = -20.4894e-6;
  const rx = secondsToRadians(-0.1502);
  const ry = secondsToRadians(-0.2470);
  const rz = secondsToRadians(-0.8421);
  return {
    x: tx + (1 + scale) * point.x - rz * point.y + ry * point.z,
    y: ty + rz * point.x + (1 + scale) * point.y - rx * point.z,
    z: tz - ry * point.x + rx * point.y + (1 + scale) * point.z,
  };
}

function helmertTransformOsgb36ToWgs84(point) {
  const tx = 446.448;
  const ty = -125.157;
  const tz = 542.060;
  const scale = 20.4894e-6;
  const rx = secondsToRadians(0.1502);
  const ry = secondsToRadians(0.2470);
  const rz = secondsToRadians(0.8421);
  return {
    x: tx + (1 + scale) * point.x - rz * point.y + ry * point.z,
    y: ty + rz * point.x + (1 + scale) * point.y - rx * point.z,
    z: tz - ry * point.x + rx * point.y + (1 + scale) * point.z,
  };
}

function cartesianToLatLon(x, y, z, a, b) {
  const e2 = 1 - (b * b) / (a * a);
  const p = Math.hypot(x, y);
  let lat = Math.atan2(z, p * (1 - e2));
  let previous;
  do {
    previous = lat;
    const nu = a / Math.sqrt(1 - e2 * Math.sin(lat) ** 2);
    lat = Math.atan2(z + e2 * nu * Math.sin(lat), p);
  } while (Math.abs(lat - previous) > 1e-12);
  return { lat, lon: Math.atan2(y, x) };
}

function osgb36LatLonToBng(lat, lon) {
  const a = 6377563.396;
  const b = 6356256.909;
  const f0 = 0.9996012717;
  const lat0 = degreesToRadians(49);
  const lon0 = degreesToRadians(-2);
  const n0 = -100000;
  const e0 = 400000;
  const e2 = 1 - (b * b) / (a * a);
  const n = (a - b) / (a + b);
  const sinLat = Math.sin(lat);
  const cosLat = Math.cos(lat);
  const tanLat = Math.tan(lat);
  const nu = a * f0 / Math.sqrt(1 - e2 * sinLat ** 2);
  const rho = a * f0 * (1 - e2) / ((1 - e2 * sinLat ** 2) ** 1.5);
  const eta2 = nu / rho - 1;
  const m = b * f0 * (
    (1 + n + 1.25 * n ** 2 + 1.25 * n ** 3) * (lat - lat0)
    - (3 * n + 3 * n ** 2 + 2.625 * n ** 3) * Math.sin(lat - lat0) * Math.cos(lat + lat0)
    + (1.875 * n ** 2 + 1.875 * n ** 3) * Math.sin(2 * (lat - lat0)) * Math.cos(2 * (lat + lat0))
    - (35 / 24) * n ** 3 * Math.sin(3 * (lat - lat0)) * Math.cos(3 * (lat + lat0))
  );
  const dLon = lon - lon0;
  const i = m + n0;
  const ii = nu / 2 * sinLat * cosLat;
  const iii = nu / 24 * sinLat * cosLat ** 3 * (5 - tanLat ** 2 + 9 * eta2);
  const iiia = nu / 720 * sinLat * cosLat ** 5 * (61 - 58 * tanLat ** 2 + tanLat ** 4);
  const iv = nu * cosLat;
  const v = nu / 6 * cosLat ** 3 * (nu / rho - tanLat ** 2);
  const vi = nu / 120 * cosLat ** 5 * (5 - 18 * tanLat ** 2 + tanLat ** 4 + 14 * eta2 - 58 * tanLat ** 2 * eta2);
  return {
    easting: e0 + iv * dLon + v * dLon ** 3 + vi * dLon ** 5,
    northing: i + ii * dLon ** 2 + iii * dLon ** 4 + iiia * dLon ** 6,
  };
}

function degreesToRadians(value) {
  return value * Math.PI / 180;
}

function radiansToDegrees(value) {
  return value * 180 / Math.PI;
}

function secondsToRadians(value) {
  return degreesToRadians(value / 3600);
}

function applyTransform() {
  const rect = elements.viewer.getBoundingClientRect();
  const scaledWidth = state.imageWidth * state.zoom;
  const scaledHeight = state.imageHeight * state.zoom;
  state.offsetX = clampAxisOffset(state.offsetX, rect.width, scaledWidth);
  state.offsetY = clampAxisOffset(state.offsetY, rect.height, scaledHeight);
  if (document.activeElement !== elements.zoomLabel) updateZoomInput();
  updateMeasurementOverlay();
  scheduleRender();
}

function scheduleRender() {
  if (state.renderRequest) return;
  state.renderRequest = requestAnimationFrame(() => {
    state.renderRequest = 0;
    renderViewport();
  });
}

function scheduleChunkRender() {
  if (state.renderRequest || state.chunkRenderRequest) return;
  state.chunkRenderRequest = window.setTimeout(() => {
    state.chunkRenderRequest = 0;
    scheduleRender();
  }, 16);
}

function renderViewport() {
  if (!state.manifest || !state.mapCanvas || !state.mapRenderer) return;
  try {
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

    prepareRendererFrame(rect, dpr);

    const crop = visibleImageCrop(rect);
    if (!crop) {
      state.activeLayerBitmapKeys = new Set();
      releaseUnusedLayerBitmaps(state.activeLayerBitmapKeys);
      return;
    }

    const activeBitmapKeys = new Set();
    const loadRequests = [];
    state.activeLayerBitmapKeys = activeBitmapKeys;
    for (const entry of state.layers.values()) {
      if (!entry.enabled) continue;
      if (entry.layer.kind === "weather-live") {
        drawLiveWeatherLayer(entry.layer, crop);
        continue;
      }
      const mip = chooseMip(entry.layer);
      const fallback = fallbackLayerRegion(entry.layer, crop, mip);
      if (fallback) {
        activeBitmapKeys.add(fallback.key);
        drawLayerBitmap(fallback, crop, dpr);
      }
      for (const region of layerDecodeRegions(crop, mip)) {
        const desiredKey = layerRegionCacheKey(entry.layer, mip, region);
        activeBitmapKeys.add(desiredKey);
        const decoded = state.layerBitmaps.get(desiredKey);
        if (decoded) {
          drawLayerBitmap(decoded, crop, dpr);
        } else {
          loadRequests.push({
            layer: entry.layer,
            mip,
            region,
            key: desiredKey,
            priority: layerLoadPriority(entry.layer, mip, region, crop),
          });
        }
      }
    }
    releaseUnusedLayerBitmaps(activeBitmapKeys);
    pruneStaleBitmapLoads();
    loadRequests.sort((a, b) => a.priority - b.priority);
    scheduleLayerLoadRequests(loadRequests);
  } catch (error) {
    if (state.mapRendererMode === "webgl") {
      fallbackToCanvasRenderer(error);
      return renderViewport();
    }
    throw error;
  }
}

function drawLiveWeatherLayer(layer, crop) {
  if (state.mapRendererMode !== "2d") return;
  const ctx = state.mapCtx;
  const live = state.liveWeather[layer.live_weather_metric];
  if (!ctx || !live?.canvas) return;
  const drawCrop = { left: crop.left, top: crop.top, right: crop.right, bottom: crop.bottom };
  const sx = drawCrop.left * live.canvas.width / state.imageWidth;
  const sy = drawCrop.top * live.canvas.height / state.imageHeight;
  const sw = (drawCrop.right - drawCrop.left) * live.canvas.width / state.imageWidth;
  const sh = (drawCrop.bottom - drawCrop.top) * live.canvas.height / state.imageHeight;
  const dx = state.offsetX + drawCrop.left * state.zoom;
  const dy = state.offsetY + drawCrop.top * state.zoom;
  const dw = (drawCrop.right - drawCrop.left) * state.zoom;
  const dh = (drawCrop.bottom - drawCrop.top) * state.zoom;
  ctx.imageSmoothingEnabled = true;
  ctx.drawImage(live.canvas, sx, sy, sw, sh, dx, dy, dw, dh);
}

function prepareRendererFrame(rect, dpr) {
  if (state.mapRendererMode === "webgl") {
    prepareWebGlFrame(rect, dpr);
    return;
  }
  prepareCanvasFrame(rect, dpr);
}

function prepareCanvasFrame(rect, dpr) {
  const ctx = state.mapCtx;
  if (!ctx) throw new Error("2D canvas context unavailable");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, rect.width, rect.height);
  ctx.fillStyle = "#101820";
  ctx.fillRect(0, 0, rect.width, rect.height);
}

function prepareWebGlFrame(rect, dpr) {
  const renderer = state.mapRenderer;
  if (!renderer || renderer.mode !== "webgl") throw new Error("WebGL renderer unavailable");
  const gl = renderer.gl;
  gl.viewport(0, 0, state.mapCanvas.width, state.mapCanvas.height);
  gl.clearColor(16 / 255, 24 / 255, 32 / 255, 1);
  gl.clear(gl.COLOR_BUFFER_BIT);
  gl.useProgram(renderer.program);
  gl.bindBuffer(gl.ARRAY_BUFFER, renderer.buffer);
  gl.uniform2f(renderer.resolutionLocation, rect.width * dpr, rect.height * dpr);
}

function visibleImageCrop(rect) {
  const scaledWidth = state.imageWidth * state.zoom;
  const scaledHeight = state.imageHeight * state.zoom;
  const fitsWidth = scaledWidth <= rect.width + 0.5;
  const fitsHeight = scaledHeight <= rect.height + 0.5;
  const left = fitsWidth ? 0 : Math.max(0, -state.offsetX / state.zoom);
  const top = fitsHeight ? 0 : Math.max(0, -state.offsetY / state.zoom);
  const right = fitsWidth ? state.imageWidth : Math.min(state.imageWidth, (rect.width - state.offsetX) / state.zoom);
  const bottom = fitsHeight ? state.imageHeight : Math.min(state.imageHeight, (rect.height - state.offsetY) / state.zoom);
  if (right <= left || bottom <= top) return null;
  return { left, top, right, bottom };
}

function ensureLayerBitmapLoad(layer, mip, region, key, priority = 0) {
  if (state.layerBitmaps.has(key) || state.layerLoads.has(key)) return;
  const load = loadLayerBitmap(layer, mip, region, key, priority).finally(() => state.layerLoads.delete(key));
  state.layerLoads.set(key, load);
}

function scheduleLayerLoadRequests(requests) {
  for (const request of requests) state.pendingLayerLoadRequests.set(request.key, request);
  if (state.chunkLoadRequestTimer) window.clearTimeout(state.chunkLoadRequestTimer);
  state.chunkLoadRequestTimer = window.setTimeout(() => {
    state.chunkLoadRequestTimer = 0;
    flushLayerLoadRequests();
  }, CHUNK_LOAD_DEBOUNCE_MS);
}

function flushLayerLoadRequests() {
  const requests = Array.from(state.pendingLayerLoadRequests.values())
    .filter((request) => state.activeLayerBitmapKeys.has(request.key))
    .sort((a, b) => a.priority - b.priority);
  state.pendingLayerLoadRequests.clear();
  for (const request of requests) {
    ensureLayerBitmapLoad(request.layer, request.mip, request.region, request.key, request.priority);
  }
}

function enqueueBitmapLoad(task, key = null, priority = 0) {
  return new Promise((resolve, reject) => {
    state.bitmapLoadQueue.push({ task, key, priority, resolve, reject });
    state.bitmapLoadQueue.sort((a, b) => a.priority - b.priority);
    pumpBitmapLoadQueue();
  });
}

function pumpBitmapLoadQueue() {
  while (state.activeBitmapLoads < MAX_CONCURRENT_BITMAP_LOADS && state.bitmapLoadQueue.length) {
    const item = state.bitmapLoadQueue.shift();
    if (item.key && !state.activeLayerBitmapKeys.has(item.key)) {
      item.resolve(null);
      continue;
    }
    state.activeBitmapLoads += 1;
    item.task()
      .then(item.resolve, item.reject)
      .finally(() => {
        state.activeBitmapLoads -= 1;
        pumpBitmapLoadQueue();
      });
  }
}

function pruneStaleBitmapLoads() {
  const pending = [];
  for (const item of state.bitmapLoadQueue) {
    if (item.key && !state.activeLayerBitmapKeys.has(item.key)) {
      item.resolve(null);
    } else {
      pending.push(item);
    }
  }
  state.bitmapLoadQueue = pending;
  for (const key of state.pendingLayerLoadRequests.keys()) {
    if (!state.activeLayerBitmapKeys.has(key)) state.pendingLayerLoadRequests.delete(key);
  }
}

function layerLoadPriority(layer, mip, region, crop) {
  const layerPriority = layer.kind === "base" ? 0 : layer.kind === "ore" ? 200000 : 100000;
  const factor = Number(mip.factor) || 1;
  const cropCenterX = (crop.left + crop.right) / (2 * factor);
  const cropCenterY = (crop.top + crop.bottom) / (2 * factor);
  const regionCenterX = (region.left + region.right) / 2;
  const regionCenterY = (region.top + region.bottom) / 2;
  return layerPriority + Math.hypot(regionCenterX - cropCenterX, regionCenterY - cropCenterY);
}

function fallbackLayerRegion(layer, crop, preferredMip) {
  const candidates = Array.from(state.layerBitmaps.values())
    .sort((a, b) => (Number(b.lastUsed) || 0) - (Number(a.lastUsed) || 0));
  for (const decoded of candidates) {
    if (!decoded.key.startsWith(`${layer.name}\n`)) continue;
    if (Number(decoded.mip.factor) === Number(preferredMip.factor)) continue;
    if (intersectImageCrop(crop, decoded)) return decoded;
  }
  return null;
}

function layerDecodeRegions(crop, mip) {
  const visible = mipVisibleRegion(crop, mip);
  const width = Number(mip.width) || Math.ceil(state.imageWidth / (Number(mip.factor) || 1));
  const height = Number(mip.height) || Math.ceil(state.imageHeight / (Number(mip.factor) || 1));
  const chunk = Number(mip.tiles?.size) || DECODE_CHUNK_PIXELS;
  const leftChunk = Math.max(0, Math.floor(visible.left / chunk) * chunk);
  const topChunk = Math.max(0, Math.floor(visible.top / chunk) * chunk);
  const rightChunk = Math.min(width, Math.ceil(visible.right / chunk) * chunk);
  const bottomChunk = Math.min(height, Math.ceil(visible.bottom / chunk) * chunk);
  const regions = [];
  for (let top = topChunk; top < bottomChunk; top += chunk) {
    for (let left = leftChunk; left < rightChunk; left += chunk) {
      if (mip.tiles) {
        regions.push({
          left,
          top,
          right: Math.min(width, left + chunk),
          bottom: Math.min(height, top + chunk),
          tileX: left / chunk,
          tileY: top / chunk,
        });
      } else {
        regions.push({
          left: Math.max(visible.left, left),
          top: Math.max(visible.top, top),
          right: Math.min(width, visible.right, left + chunk),
          bottom: Math.min(height, visible.bottom, top + chunk),
        });
      }
    }
  }
  return regions;
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

function intersectImageCrop(crop, decoded) {
  const factor = Number(decoded.mip.factor) || 1;
  const decodedCrop = {
    left: decoded.region.left * factor,
    top: decoded.region.top * factor,
    right: decoded.region.right * factor,
    bottom: decoded.region.bottom * factor,
  };
  const left = Math.max(crop.left, decodedCrop.left);
  const top = Math.max(crop.top, decodedCrop.top);
  const right = Math.min(crop.right, decodedCrop.right);
  const bottom = Math.min(crop.bottom, decodedCrop.bottom);
  if (right <= left || bottom <= top) return null;
  return { left, top, right, bottom };
}

async function loadLayerBitmap(layer, mip, region, key, priority) {
  const bitmap = await enqueueBitmapLoad(async () => {
    if (!state.activeLayerBitmapKeys.has(key)) return null;
    const url = layerRegionUrl(layer, mip, region);
    const blob = mip.tiles ? await fetchBlob(url) : await loadSourceBlob(url);
    if (!state.activeLayerBitmapKeys.has(key)) return null;
    if (mip.tiles) return createLayerImageBitmap(blob);
    return createLayerImageBitmap(blob, region.left, region.top, region.right - region.left, region.bottom - region.top);
  }, key, priority);
  if (!bitmap) return;
  const entry = state.layers.get(layer.name);
  if (!entry?.enabled || !state.activeLayerBitmapKeys.has(key) || !key.startsWith(`${layerCacheKey(layer, chooseMip(layer))}\n`)) {
    if (typeof bitmap.close === "function") bitmap.close();
    return;
  }
  state.layerBitmaps.set(key, { key, bitmap, mip, region, lastUsed: performance.now() });
  pruneLayerBitmapCache(state.activeLayerBitmapKeys);
  scheduleChunkRender();
}

function createLayerImageBitmap(source, ...args) {
  const options = state.mapRendererMode === "webgl"
    ? { colorSpaceConversion: "none", premultiplyAlpha: "none" }
    : null;
  if (!options) return createImageBitmap(source, ...args);
  try {
    return createImageBitmap(source, ...args, options);
  } catch (_error) {
    return createImageBitmap(source, ...args);
  }
}

async function loadSourceBlob(url) {
  if (state.sourceBlobs.has(url)) return state.sourceBlobs.get(url);
  const load = fetchBlob(url);
  state.sourceBlobs.set(url, load);
  try {
    return await load;
  } catch (error) {
    state.sourceBlobs.delete(url);
    throw error;
  }
}

async function fetchBlob(url) {
  const response = await fetch(url, { cache: "force-cache" });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
  return response.blob();
}

function drawLayerBitmap(decoded, crop, dpr) {
  if (state.mapRendererMode === "webgl") {
    drawLayerBitmapWebGl(decoded, crop, dpr);
    return;
  }
  drawLayerBitmap2d(decoded, crop);
}

function drawLayerBitmap2d(decoded, crop) {
  const ctx = state.mapCtx;
  if (!ctx) return;
  const drawCrop = intersectImageCrop(crop, decoded);
  if (!drawCrop) return;
  decoded.lastUsed = performance.now();
  const factor = Number(decoded.mip.factor) || 1;
  const sx = drawCrop.left / factor - decoded.region.left;
  const sy = drawCrop.top / factor - decoded.region.top;
  const sw = (drawCrop.right - drawCrop.left) / factor;
  const sh = (drawCrop.bottom - drawCrop.top) / factor;
  const dx = state.offsetX + drawCrop.left * state.zoom;
  const dy = state.offsetY + drawCrop.top * state.zoom;
  const dw = (drawCrop.right - drawCrop.left) * state.zoom;
  const dh = (drawCrop.bottom - drawCrop.top) * state.zoom;
  ctx.imageSmoothingEnabled = !(Number(decoded.mip.factor) === 1 && state.zoom >= 1);
  ctx.drawImage(decoded.bitmap, sx, sy, sw, sh, dx, dy, dw, dh);
}

function drawLayerBitmapWebGl(decoded, crop, dpr) {
  const renderer = state.mapRenderer;
  if (!renderer || renderer.mode !== "webgl") return;
  const drawCrop = intersectImageCrop(crop, decoded);
  if (!drawCrop) return;
  decoded.lastUsed = performance.now();
  const factor = Number(decoded.mip.factor) || 1;
  const sx = drawCrop.left / factor - decoded.region.left;
  const sy = drawCrop.top / factor - decoded.region.top;
  const sw = (drawCrop.right - drawCrop.left) / factor;
  const sh = (drawCrop.bottom - drawCrop.top) / factor;
  const dx = (state.offsetX + drawCrop.left * state.zoom) * dpr;
  const dy = (state.offsetY + drawCrop.top * state.zoom) * dpr;
  const dw = (drawCrop.right - drawCrop.left) * state.zoom * dpr;
  const dh = (drawCrop.bottom - drawCrop.top) * state.zoom * dpr;
  const textureInfo = ensureWebGlTexture(decoded);
  const gl = renderer.gl;
  const smoothing = !(Number(decoded.mip.factor) === 1 && state.zoom >= 1);
  configureTextureFiltering(gl, textureInfo, smoothing);

  const u1 = sx / decoded.bitmap.width;
  const v1 = sy / decoded.bitmap.height;
  const u2 = (sx + sw) / decoded.bitmap.width;
  const v2 = (sy + sh) / decoded.bitmap.height;
  const x1 = dx;
  const y1 = dy;
  const x2 = dx + dw;
  const y2 = dy + dh;
  const vertices = new Float32Array([
    x1, y1, u1, v1,
    x2, y1, u2, v1,
    x1, y2, u1, v2,
    x1, y2, u1, v2,
    x2, y1, u2, v1,
    x2, y2, u2, v2,
  ]);

  gl.bindTexture(gl.TEXTURE_2D, textureInfo.texture);
  gl.bindBuffer(gl.ARRAY_BUFFER, renderer.buffer);
  gl.bufferData(gl.ARRAY_BUFFER, vertices, gl.STREAM_DRAW);
  gl.enableVertexAttribArray(renderer.positionLocation);
  gl.vertexAttribPointer(renderer.positionLocation, 2, gl.FLOAT, false, 16, 0);
  gl.enableVertexAttribArray(renderer.texCoordLocation);
  gl.vertexAttribPointer(renderer.texCoordLocation, 2, gl.FLOAT, false, 16, 8);
  gl.uniform1i(renderer.textureLocation, 0);
  gl.drawArrays(gl.TRIANGLES, 0, 6);
}

function ensureWebGlTexture(decoded) {
  const renderer = state.mapRenderer;
  if (!renderer || renderer.mode !== "webgl") throw new Error("WebGL renderer unavailable");
  const existing = state.layerTextures.get(decoded.key);
  if (existing) {
    existing.lastUsed = performance.now();
    return existing;
  }
  const gl = renderer.gl;
  const texture = gl.createTexture();
  if (!texture) throw new Error("Failed to create WebGL texture");
  gl.activeTexture(gl.TEXTURE0);
  gl.bindTexture(gl.TEXTURE_2D, texture);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, decoded.bitmap);
  const textureInfo = { key: decoded.key, gl, texture, smoothing: true, lastUsed: performance.now() };
  state.layerTextures.set(decoded.key, textureInfo);
  return textureInfo;
}

function configureTextureFiltering(gl, textureInfo, smoothing) {
  if (textureInfo.smoothing === smoothing) return;
  gl.bindTexture(gl.TEXTURE_2D, textureInfo.texture);
  const filter = smoothing ? gl.LINEAR : gl.NEAREST;
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
  textureInfo.smoothing = smoothing;
}

function deleteLayerTexture(key) {
  const textureInfo = state.layerTextures.get(key);
  if (!textureInfo) return;
  textureInfo.gl.deleteTexture(textureInfo.texture);
  state.layerTextures.delete(key);
}

function releaseAllLayerTextures() {
  for (const textureInfo of state.layerTextures.values()) {
    textureInfo.gl.deleteTexture(textureInfo.texture);
  }
  state.layerTextures.clear();
}

function releaseLayerBitmaps(layerName) {
  for (const [key, decoded] of state.layerBitmaps) {
    if (!key.startsWith(`${layerName}\n`)) continue;
    deleteLayerTexture(key);
    if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();
    state.layerBitmaps.delete(key);
  }
}

function releaseUnusedLayerBitmaps(activeKeys) {
  pruneLayerBitmapCache(activeKeys);
}

function pruneLayerBitmapCache(activeKeys = new Set()) {
  if (state.layerBitmaps.size <= MAX_TILE_BITMAPS) return;

  const candidates = Array.from(state.layerBitmaps.entries())
    .filter(([key]) => !activeKeys.has(key))
    .sort((a, b) => (Number(a[1].lastUsed) || 0) - (Number(b[1].lastUsed) || 0));

  for (const [key, decoded] of candidates) {
    if (state.layerBitmaps.size <= MAX_TILE_BITMAPS) break;
    deleteLayerTexture(key);
    if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();
    state.layerBitmaps.delete(key);
  }
}

function clearBitmapCaches() {
  releaseAllLayerTextures();
  for (const decoded of state.layerBitmaps.values()) {
    if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();
  }
  state.layerBitmaps.clear();
  state.layerLoads.clear();
  state.sourceBlobs.clear();
  for (const item of state.bitmapLoadQueue) item.resolve(null);
  state.bitmapLoadQueue = [];
  state.pendingLayerLoadRequests.clear();
  state.activeLayerBitmapKeys = new Set();
  if (state.chunkLoadRequestTimer) {
    window.clearTimeout(state.chunkLoadRequestTimer);
    state.chunkLoadRequestTimer = 0;
  }
  if (state.chunkRenderRequest) {
    window.clearTimeout(state.chunkRenderRequest);
    state.chunkRenderRequest = 0;
  }
  for (const sample of state.samples.values()) {
    sample.canvas.width = 0;
    sample.canvas.height = 0;
  }
  state.samples.clear();
  state.sampleLoads.clear();
  for (const tile of state.sampleTiles.values()) {
    tile.canvas.width = 0;
    tile.canvas.height = 0;
  }
  state.sampleTiles.clear();
  state.sampleTileLoads.clear();
  state.sampleGeneration += 1;
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
  const point = statusPointFromImage(image.x, image.y);
  const samplePoint = {
    ...point,
    clientX: event.clientX,
    clientY: event.clientY,
  };
  if (image.x < 0 || image.y < 0 || image.x >= state.imageWidth || image.y >= state.imageHeight) {
    return samplePoint;
  }
  if (!isInsideHeightDataBounds(image.x, image.y)) {
    return {
      ...samplePoint,
      height: null,
      minecraftHeight: 62,
    };
  }
  const scale = Number(state.manifest.scale || 1);
  const height = samplePixel("height", image.x, image.y);
  const minecraftHeight = minecraftHeightFromRawHeight(height);
  return {
    ...samplePoint,
    height,
    minecraftHeight,
  };
}

function statusPointFromImage(imageX, imageY) {
  const scale = Number(state.manifest?.scale || 1);
  const world = state.manifest?.world || {};
  const tileSize = Number(state.manifest?.tile_size || 512);
  const dataX = Math.floor(imageX * scale);
  const dataZ = Math.floor(imageY * scale);
  return {
    imageX,
    imageY,
    dataX,
    dataZ,
    minecraftX: Number(world.minecraft_min_x || 0) + dataX,
    minecraftZ: Number(world.minecraft_min_z || 0) + dataZ,
    tileX: Math.floor(dataX / tileSize),
    tileZ: Math.floor(dataZ / tileSize),
    localX: ((dataX % tileSize) + tileSize) % tileSize,
    localZ: ((dataZ % tileSize) + tileSize) % tileSize,
    insideWorld: imageX >= 0 && imageY >= 0 && imageX < state.imageWidth && imageY < state.imageHeight,
  };
}

function updateStatus(event) {
  state.lastStatusPoint = { clientX: event.clientX, clientY: event.clientY };
  const sample = sampleFromEvent(event);
  const heightText = minecraftHeightText(sample);
  const bng = bngText(sample.dataX, sample.dataZ);
  const details = statusDetails(sample);
  setStatus(`Minecraft x ${sample.minecraftX}, y ${heightText}, z ${sample.minecraftZ} | data ${sample.dataX},${sample.dataZ} | tile ${String(sample.tileX).padStart(3, "0")}_${String(sample.tileZ).padStart(3, "0")} cell ${sample.localX},${sample.localZ}${bng}${details}`);
}

function bngText(dataX, dataZ) {
  const geo = state.manifest.georeferencing || {};
  const world = state.manifest.world || {};
  if ([geo.bng_min_easting, geo.bng_max_easting, geo.bng_min_northing, geo.bng_max_northing].some((value) => value === undefined)) return "";
  const easting = Number(geo.bng_min_easting) + (dataX + 0.5) * (Number(geo.bng_max_easting) - Number(geo.bng_min_easting)) / Number(world.width);
  const northing = Number(geo.bng_max_northing) - (dataZ + 0.5) * (Number(geo.bng_max_northing) - Number(geo.bng_min_northing)) / Number(world.depth);
  return ` | BNG E ${easting.toFixed(0)}, N ${northing.toFixed(0)}`;
}

function minecraftHeightText(sample) {
  if (sample.height === undefined) return "loading…";
  if (sample.height === null || sample.minecraftHeight === null || sample.minecraftHeight === undefined) return "62";
  return `${sample.minecraftHeight}`;
}

function minecraftHeightFromRawHeight(rawHeightDecimetres) {
  if (rawHeightDecimetres === undefined) return undefined;
  if (rawHeightDecimetres === null) return null;

  const metres = rawHeightDecimetres * 0.1;
  const model = minecraftHeightModel();
  const highlandDenominator = model.highlandFullMetres - model.highlandStartMetres;
  const highlandWeight = smoothstepNumber(
    highlandDenominator === 0
      ? (metres >= model.highlandStartMetres ? 1 : 0)
      : (metres - model.highlandStartMetres) / highlandDenominator
  );
  const lowlandWeight = metres <= 0
    ? 1
    : 1 - clampNumber(metres / model.lowlandCeilingMetres, 0, 1);
  const lowlandScale = model.heightScale + model.lowlandExtraScale * lowlandWeight;
  const finalScale = lerpNumber(lowlandScale, model.highlandScale, highlandWeight);

  return model.seaLevelY + Math.round(metres * finalScale);
}

function minecraftHeightModel() {
  const manifest = state.manifest || {};
  const world = manifest.world || {};
  const height = manifest.height || {};
  const generator = manifest.generator || manifest.worldgen || manifest.ukgeo_generator || {};
  const model = manifest.minecraft_height || manifest.height_model || world.height_model || height.minecraft || generator.height || {};

  return {
    seaLevelY: numberFromModel(model, world, height, generator, "sea_level_y", "seaLevelY", 64),
    heightScale: numberFromModel(model, world, height, generator, "height_scale", "heightScale", 0.18),
    lowlandExtraScale: numberFromModel(model, world, height, generator, "lowland_extra_scale", "lowlandExtraScale", 0.03),
    lowlandCeilingMetres: numberFromModel(model, world, height, generator, "lowland_ceiling_metres", "lowlandCeilingMetres", 120.0),
    highlandScale: numberFromModel(model, world, height, generator, "highland_scale", "highlandScale", 0.2),
    highlandStartMetres: numberFromModel(model, world, height, generator, "highland_start_metres", "highlandStartMetres", 120.0),
    highlandFullMetres: numberFromModel(model, world, height, generator, "highland_full_metres", "highlandFullMetres", 750.0),
  };
}

function numberFromModel(model, world, height, generator, snakeKey, camelKey, fallback) {
  const candidates = [
    model?.[snakeKey], model?.[camelKey],
    generator?.[snakeKey], generator?.[camelKey],
    height?.[snakeKey], height?.[camelKey],
    world?.[snakeKey], world?.[camelKey],
    state.manifest?.[snakeKey], state.manifest?.[camelKey],
  ];

  for (const value of candidates) {
    const number = Number(value);
    if (Number.isFinite(number)) return number;
  }
  return fallback;
}

function clampNumber(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function smoothstepNumber(value) {
  const t = clampNumber(value, 0, 1);
  return t * t * (3 - 2 * t);
}

function lerpNumber(start, end, amount) {
  return start + (end - start) * amount;
}

function statusDetails(sample) {
  const overlays = [];
  const ores = [];
  const animalCandidates = [];

  for (const entry of state.layers.values()) {
    if (entry.layer.kind === "base") continue;

    const isAnimalLayer = isAnimalSourceLayer(entry.layer);
    if (!entry.enabled && !isAnimalLayer) continue;

    const value = samplePixel(
        entry.layer.name,
        sample.imageX,
        sample.imageY
    );

    if (value === null || value === undefined || value === 0) continue;

    if (entry.layer.kind === "ore") {
      if (!entry.enabled) continue;
      const oreText = oreAmountText(entry.layer.ore, value);
      if (oreText) ores.push(`${entry.layer.ore}: ${oreText}`);
    } else if (entry.layer.kind === "animal") {
      continue;
    } else {
      const label = overlayValueLabel(entry.layer, value);
      if (entry.enabled) overlays.push(`${labelFor(entry.layer.name)}: ${label}`);

      // Animal lookup must not depend on the overlay being visible. Use the
      // same sampled class plus aliases from the manifest class table.
      if (isAnimalLayer) {
        animalCandidates.push(...classCandidateValues(entry.layer.name, value));
      }
    }
  }

  const parts = [];
  if (overlays.length) parts.push(overlays.join(" | "));
  if (ores.length) parts.push(`Ores ${ores.join(", ")}`);
  const animalText = animalsForSample(sample, animalCandidates);
  if (animalText && animalText !== "—") parts.push(`Animals: ${animalText}`);

  return parts.length ? ` | ${parts.join(" | ")}` : "";
}

function isAnimalSourceLayer(layer) {
  const name = normaliseAnimalKey(layer?.name || "");
  const kind = normaliseAnimalKey(layer?.kind || "");
  const label = normaliseAnimalKey(layer?.label || layer?.title || "");
  return (
    name === "vegetation" ||
    name === "biome" ||
    name === "biomes" ||
    name === "landcover" ||
    name === "land_cover" ||
    name.includes("vegetation") ||
    name.includes("biome") ||
    name.includes("landcover") ||
    name.includes("land_cover") ||
    kind === "vegetation" ||
    kind === "biome" ||
    kind === "landcover" ||
    kind === "land_cover" ||
    label.includes("vegetation") ||
    label.includes("biome") ||
    label.includes("landcover") ||
    label.includes("land_cover")
  );
}

function classCandidateValues(layerName, value) {
  const values = [];
  const add = (candidate) => {
    if (candidate === undefined || candidate === null || candidate === "") return;
    values.push(candidate);
  };

  add(value);
  add(String(value));
  add(classLabel(layerName, value));

  const classInfo = classInfoFor(layerName, value);
  if (classInfo && typeof classInfo === "object") {
    add(classInfo.name);
    add(classInfo.label);
    add(classInfo.id);
    add(classInfo.key);
    add(classInfo.biome);
    add(classInfo.biome_id);
    add(classInfo.biomeId);
    add(classInfo.landcover);
    add(classInfo.landcover_class);
    add(classInfo.landcoverClass);
  }

  return values;
}

function classInfoFor(layerName, value) {
  const keys = classManifestKeys(layerName);
  for (const key of keys) {
    const classes = state.manifest?.[key]?.classes;
    const info = classes?.[String(value)];
    if (info) return info;
  }
  return null;
}

function classManifestKeys(layerName) {
  const keys = [];
  const add = (key) => {
    if (key && !keys.includes(key)) keys.push(key);
  };

  const normalised = normaliseAnimalKey(layerName);
  add(layerName);
  add(normalised);
  if (layerName === "surface" || normalised === "surface") add("surface_geology");
  if (isAnimalSourceLayer({ name: layerName })) {
    add("biome_regions");
    add("biome_region");
    add("vegetation");
    add("biome");
    add("biomes");
    add("landcover");
    add("land_cover");
  }

  return keys;
}

function oreAmountText(oreName, score) {
  const settings = ORE_ATTEMPT_SETTINGS[oreName];
  if (!settings || score <= 0) return null;

  const normalAttempts = settings.base + Math.round(settings.maxBonus * (score / 255));
  const backgroundAttempts = (settings.base + settings.maxBonus) * BACKGROUND_ORE_ATTEMPT_MULTIPLIER;

  if (backgroundAttempts <= 0) return null;

  const oreAreaAttempts = normalAttempts * ORE_AREA_ATTEMPT_MULTIPLIER;
  const rawMultiplier = oreAreaAttempts / backgroundAttempts;
  const displayMultiplier = rawMultiplier / ORE_DISPLAY_MULTIPLIER_DIVISOR;
  return `${formatMultiplier(displayMultiplier)}x`;
}

function formatMultiplier(multiplier) {
  if (!Number.isFinite(multiplier)) return "0";

  const decimals = multiplier >= 10 || Number.isInteger(multiplier) ? 0 : 1;
  return multiplier.toFixed(decimals);
}

function classLabel(layerName, value) {
  const info = classInfoFor(layerName, value);
  return info?.name || info?.label || info?.id || info?.key || String(value);
}

function overlayValueLabel(layer, value) {
  if (layer?.value_format === "percent") {
    return `${Math.round(Number(value) || 0)}%`;
  }
  return classLabel(layer?.name || "", value);
}

function resetLiveWeatherState() {
  if (state.liveWeather.timer) {
    window.clearTimeout(state.liveWeather.timer);
  }
  state.liveWeather = {
    timer: 0,
    loading: false,
    cloud_cover: null,
    downfall_coverage: null,
  };
}

async function fetchLiveWeather(manifest) {
  const config = manifest?.live_weather;
  const grid = config?.grid;
  if (!config || !grid?.latitudes?.length || !grid?.longitudes?.length) return;
  if (state.liveWeather.loading) return;
  state.liveWeather.loading = true;
  try {
    const payloads = await fetchOpenMeteoWeather(config);
    const metrics = decodeLiveWeatherMetrics(config, payloads);
    state.liveWeather.cloud_cover = createLiveWeatherMetric("cloud_cover", metrics.cloud_cover, grid.columns, grid.rows);
    state.liveWeather.downfall_coverage = createLiveWeatherMetric("downfall_coverage", metrics.downfall_coverage, grid.columns, grid.rows);
    scheduleRender();
    refreshStatus();
  } finally {
    state.liveWeather.loading = false;
    if (state.liveWeather.timer) window.clearTimeout(state.liveWeather.timer);
    state.liveWeather.timer = window.setTimeout(() => {
      fetchLiveWeather(manifest).catch((error) => console.warn("[hoverpreview] Live weather refresh failed", error));
    }, LIVE_WEATHER_REFRESH_MS);
  }
}

async function fetchOpenMeteoWeather(config) {
  const baseUrl = String(config.api_base_url || "https://api.open-meteo.com/v1/forecast");
  const model = String(config.weather_model || "auto").trim();
  const batchPoints = Math.max(1, Number(config.batch_points) || 64);
  const latitudes = Array.from(config.grid.latitudes || [], (value) => Number(value));
  const longitudes = Array.from(config.grid.longitudes || [], (value) => Number(value));
  const results = [];
  for (let start = 0; start < latitudes.length; start += batchPoints) {
    const batchLatitudes = latitudes.slice(start, start + batchPoints);
    const batchLongitudes = longitudes.slice(start, start + batchPoints);
    const url = new URL(baseUrl, location.href);
    url.searchParams.set("latitude", batchLatitudes.map((value) => value.toFixed(6)).join(","));
    url.searchParams.set("longitude", batchLongitudes.map((value) => value.toFixed(6)).join(","));
    url.searchParams.set("current", "cloud_cover");
    url.searchParams.set("hourly", "precipitation_probability");
    url.searchParams.set("forecast_hours", "1");
    url.searchParams.set("timezone", "GMT");
    url.searchParams.set("timeformat", "unixtime");
    if (model && model.toLowerCase() !== "auto") {
      url.searchParams.set("models", model);
    }
    const response = await fetch(url.href, { cache: "no-store" });
    if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
    const payload = await response.json();
    if (!Array.isArray(payload)) throw new Error(`Expected Open-Meteo multi-location response array, got ${typeof payload}`);
    results.push(...payload);
  }
  return results;
}

function decodeLiveWeatherMetrics(config, payloads) {
  const total = Number(config?.grid?.rows) * Number(config?.grid?.columns);
  const cloud = new Uint8Array(total);
  const downfall = new Uint8Array(total);
  payloads.forEach((location, index) => {
    const current = location?.current || {};
    const hourly = location?.hourly || {};
    const probabilities = Array.isArray(hourly.precipitation_probability) ? hourly.precipitation_probability : [];
    cloud[index] = clampPercent(current.cloud_cover);
    downfall[index] = clampPercent(probabilities.length ? probabilities[0] : 0);
  });
  return { cloud_cover: cloud, downfall_coverage: downfall };
}

function clampPercent(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0;
  return Math.max(0, Math.min(100, Math.round(number)));
}

function createLiveWeatherMetric(metricName, values, columns, rows) {
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, columns);
  canvas.height = Math.max(1, rows);
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  const imageData = ctx.createImageData(canvas.width, canvas.height);
  for (let index = 0; index < values.length; index += 1) {
    const base = index * 4;
    const value = values[index];
    if (metricName === "cloud_cover") {
      const shade = Math.round(232 - (232 - 108) * (value / 100));
      const alpha = Math.round(176 * (value / 100));
      imageData.data[base + 0] = shade;
      imageData.data[base + 1] = shade;
      imageData.data[base + 2] = shade;
      imageData.data[base + 3] = alpha;
    } else {
      const alpha = Math.round(208 * (value / 100));
      imageData.data[base + 0] = 76;
      imageData.data[base + 1] = 148;
      imageData.data[base + 2] = 255;
      imageData.data[base + 3] = alpha;
    }
  }
  ctx.putImageData(imageData, 0, 0);
  return { metricName, values, columns, rows, canvas };
}

function animalsListUrls(manifest) {
  const params = new URLSearchParams(location.search);
  const queryValue = params.get("animals");
  const urls = [];
  const add = (value, base) => {
    if (!value) return;
    const href = new URL(value, base || location.href).href;
    if (!urls.some((url) => url.href === href)) urls.push(new URL(href));
  };

  add(queryValue, location.href);
  add(manifest.animals_file, state.baseUrl || location.href);
  add(manifest.animals?.file, state.baseUrl || location.href);
  add(DEFAULT_ANIMALS_LIST, location.href);
  add(DEFAULT_ANIMALS_LIST, state.baseUrl || location.href);

  return urls;
}

async function loadAnimalsList(manifest) {
  // Use a built-in list immediately so the hover UI works even when this page is
  // served from a wrapper path that cannot fetch ./animals.txt. A fetched
  // animals.txt still overrides this fallback below.
  state.animals = parseAnimalsList(DEFAULT_ANIMALS_TEXT);
  state.animalsLoaded = state.animals.size > 0;
  state.animalsLoadError = null;

  const urls = animalsListUrls(manifest);
  let lastError = null;

  for (const url of urls) {
    try {
      const response = await fetch(url.href, { cache: "no-cache" });
      if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
      const text = await response.text();
      const fetchedAnimals = parseAnimalsList(text);
      state.animals = new Map([...parseAnimalsList(DEFAULT_ANIMALS_TEXT), ...fetchedAnimals]);
      state.animalsLoaded = state.animals.size > 0;
      state.animalsLoadError = null;
      console.info(`[hoverpreview] Loaded ${fetchedAnimals.size} animal keys from ${url.href} (${state.animals.size} keys including built-in aliases)`);
      refreshStatus();
      return;
    } catch (error) {
      lastError = error;
      debugAnimals("Could not load animal list candidate", { url: url.href, error });
    }
  }

  state.animalsLoadError = lastError;
  console.warn(`[hoverpreview] Could not load animal list from any candidate; using built-in animal list`, urls.map((url) => url.href), lastError);
  refreshStatus();
}

function parseAnimalsList(text) {
  const rules = new Map();
  const lines = String(text || "").split(/\r?\n/);

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;

    const equalsIndex = line.indexOf("=");
    const colonIndex = line.lastIndexOf(":");
    const splitAt = equalsIndex >= 0 ? equalsIndex : colonIndex;
    if (splitAt < 0) continue;

    const keyText = line.slice(0, splitAt).trim();
    const animalText = line.slice(splitAt + 1).trim();
    if (!keyText || !animalText) continue;

    for (const alias of keyText.split("|")) {
      for (const key of animalKeyVariants(alias)) {
        if (key) rules.set(key, animalText);
      }
    }
  }

  return rules;
}

function animalsForSample(sample, candidates = []) {
  if (!state.animalsLoaded || !state.animals.size) return "—";

  const allCandidates = [];
  const addCandidate = (value) => {
    if (value === undefined || value === null || value === "") return;
    allCandidates.push(value);
  };

  // Simple source of truth: the animals list corresponds to the vegetation class
  // under the cursor. Read the vegetation/landcover/biome sample layer directly
  // and try both its numeric class ID and its manifest class name/aliases.
  const animalSourceEntries = Array.from(state.layers.values())
    .filter((entry) => isAnimalSourceLayer(entry.layer))
    .sort((a, b) => animalSourcePriority(a.layer) - animalSourcePriority(b.layer));
  for (const entry of animalSourceEntries) {
    for (const candidate of animalCandidatesFromLayer(entry.layer.name, sample.imageX, sample.imageY)) {
      addCandidate(candidate);
    }
  }

  // Secondary source: values collected while the status text was built.
  for (const candidate of candidates) addCandidate(candidate);
  for (const field of [
    "vegetation", "vegetationClass", "vegetationClassId", "vegetationId", "vegetationName",
    "biome", "biomeId", "biomeName", "landcover", "landcoverClass", "label"
  ]) {
    addCandidate(sample?.[field]);
  }

  for (const candidate of allCandidates) {
    for (const key of animalKeyVariants(candidate)) {
      const animals = state.animals.get(key);
      if (animals) {
        debugAnimals("Matched animals", { candidate, key, animals });
        return animals;
      }
    }
  }

  debugAnimals("No animals match", { candidates: allCandidates, keys: allCandidates.flatMap(animalKeyVariants), loadedKeys: Array.from(state.animals.keys()) });
  return "—";
}

function animalSourcePriority(layer) {
  const name = normaliseAnimalKey(layer?.name || "");
  if (name.includes("biome_region") || name === "biome" || name === "biomes") return 0;
  if (name.includes("vegetation") || name.includes("landcover") || name.includes("land_cover")) return 1;
  return 2;
}

function animalCandidatesFromLayer(layerName, imageX, imageY) {
  const candidates = [];
  const add = (value) => {
    if (value === undefined || value === null || value === "") return;
    candidates.push(value);
  };

  const rawValue = samplePixel(layerName, imageX, imageY);
  if (rawValue !== undefined && rawValue !== null) {
    for (const candidate of classCandidateValues(layerName, rawValue)) add(candidate);
  }

  // If the sample image is colour-coded rather than raw U8 class IDs, the red
  // channel alone is not the vegetation class. Match the full sampled colour to
  // the manifest's class colours and then use that class ID/name.
  const rgba = samplePixelRgba(layerName, imageX, imageY);
  for (const classId of classIdsForSampleColor(layerName, rgba)) {
    for (const candidate of classCandidateValues(layerName, classId)) add(candidate);
  }

  return candidates;
}

function classIdsForSampleColor(layerName, rgba) {
  if (!rgba) return [];
  const matches = [];
  const seen = new Set();

  for (const key of classManifestKeys(layerName)) {
    const classes = state.manifest?.[key]?.classes || {};
    for (const [classId, info] of Object.entries(classes)) {
      const color = parseClassColor(info?.color || info?.colour || info?.hex);
      if (!color) continue;
      const distance = Math.abs(color[0] - rgba[0]) + Math.abs(color[1] - rgba[1]) + Math.abs(color[2] - rgba[2]);
      if (distance <= 6 && !seen.has(classId)) {
        seen.add(classId);
        matches.push(classId);
      }
    }
  }

  return matches;
}

function parseClassColor(value) {
  const text = String(value || "").trim();
  if (!text) return null;

  const hex = text.startsWith("#") ? text.slice(1) : text;
  if (/^[0-9a-fA-F]{6}$/.test(hex)) {
    return [
      Number.parseInt(hex.slice(0, 2), 16),
      Number.parseInt(hex.slice(2, 4), 16),
      Number.parseInt(hex.slice(4, 6), 16),
    ];
  }

  const rgb = text.match(/rgba?\((\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i);
  if (rgb) return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])];

  return null;
}

function normaliseAnimalKey(value) {
  return String(value ?? "")
    .trim()
    .toLowerCase()
    .replace(/^#/, "")
    .replace(/&/g, "and")
    .replace(/[-\s]+/g, "_")
    .replace(/[^a-z0-9:_]+/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_+|_+$/g, "");
}

function animalKeyVariants(value) {
  const base = normaliseAnimalKey(value);
  const variants = new Set();

  if (base) variants.add(base);

  const colon = base.indexOf(":");
  if (colon >= 0 && colon + 1 < base.length) {
    variants.add(base.slice(colon + 1));
  }

  return Array.from(variants);
}

function debugAnimals(message, details) {
  if (window.localStorage?.getItem("ukgeo.debugAnimals") === "1") {
    console.debug(`[hoverpreview] ${message}`, details);
  }
}

function refreshStatus() {
  if (!state.lastStatusPoint) return;
  updateStatus({
    clientX: state.lastStatusPoint.clientX,
    clientY: state.lastStatusPoint.clientY,
  });
}

function updateMeasurement(event) {
  const start = state.measureStart;
  const current = sampleFromEvent(event);
  if (!start || !current) {
    clearMeasurement();
    return;
  }
  if (Math.abs(current.clientX - start.clientX) + Math.abs(current.clientY - start.clientY) >= 4) state.measureMoved = true;
  if (!state.measureMoved) return;
  const line = ensureMeasurement();
  const dx = current.minecraftX - start.minecraftX;
  const dz = current.minecraftZ - start.minecraftZ;
  const distance = Math.hypot(dx, dz);
  const heightDelta = Number.isFinite(current.minecraftHeight) && Number.isFinite(start.minecraftHeight)
    ? `, dh ${current.minecraftHeight - start.minecraftHeight} blocks`
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

async function loadSample(layer, imageX = null, imageY = null) {
  if (layer.sample_tiles && Number.isFinite(imageX) && Number.isFinite(imageY)) {
    return loadSampleTile(layer, imageX, imageY);
  }

  const sampleFile = layer.browser_sample_file || layer.sample_file;
  if (!sampleFile && !layer.sample_tiles) return;
  const crop = sampleCropFor(layer, imageX, imageY);
  const existing = state.samples.get(layer.name);
  if (existing && sampleContains(existing, imageX, imageY)) return;
  const key = `${layer.name}\n${crop.left},${crop.top},${crop.width},${crop.height}`;
  if (state.sampleLoads.has(key)) return state.sampleLoads.get(key);
  const generation = state.sampleGeneration;
  const load = (async () => {
    const decoded = await enqueueBitmapLoad(async () => {
      const entry = state.layers.get(layer.name);
      if (layer.name !== "height" && (!entry || !shouldLoadSample(entry))) return null;
      const blob = crop.tile
        ? await fetchBlob(sampleRegionUrl(layer, crop))
        : await loadSourceBlob(assetUrl(sampleFile));
      return decodeSampleBitmap(blob, crop);
    }, null, SAMPLE_LOAD_PRIORITY);
    if (!decoded) return;
    if (generation !== state.sampleGeneration) {
      if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();
      return;
    }
    const entry = state.layers.get(layer.name);
    if (layer.name !== "height" && (!entry || !shouldLoadSample(entry))) {
      if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();
      return;
    }
    const canvas = document.createElement("canvas");
    canvas.width = decoded.width;
    canvas.height = decoded.height;
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    ctx.drawImage(decoded.bitmap, decoded.sourceX, decoded.sourceY, decoded.width, decoded.height, 0, 0, decoded.width, decoded.height);
    if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();
    releaseSample(layer.name);
    state.samples.set(layer.name, {
      canvas,
      ctx,
      width: canvas.width,
      height: canvas.height,
      originX: crop.left,
      originY: crop.top,
      encoding: sampleEncodingFor(layer),
    });
    if (state.lastStatusPoint) requestAnimationFrame(() => updateStatus(state.lastStatusPoint));
  })().finally(() => state.sampleLoads.delete(key));
  state.sampleLoads.set(key, load);
  return load;
}

async function loadSampleTile(layer, imageX, imageY) {
  const tile = sampleTileFor(layer, imageX, imageY);
  if (!tile) return;
  const key = sampleTileCacheKey(layer, tile.tileX, tile.tileY);
  if (state.sampleTiles.has(key)) return;
  if (state.sampleTileLoads.has(key)) return state.sampleTileLoads.get(key);
  const generation = state.sampleGeneration;

  const load = (async () => {
    const decoded = await enqueueBitmapLoad(async () => {
      const entry = state.layers.get(layer.name);
      if (layer.name !== "height" && (!entry || !shouldLoadSample(entry))) return null;
      const blob = await fetchBlob(sampleRegionUrl(layer, tile));
      const bitmap = await createImageBitmap(blob);
      return { bitmap, width: bitmap.width, height: bitmap.height };
    }, null, SAMPLE_LOAD_PRIORITY);

    if (!decoded) return;
    if (generation !== state.sampleGeneration) {
      if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();
      return;
    }
    const entry = state.layers.get(layer.name);
    if (layer.name !== "height" && (!entry || !shouldLoadSample(entry))) {
      if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();
      return;
    }

    const canvas = document.createElement("canvas");
    canvas.width = decoded.width;
    canvas.height = decoded.height;
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    ctx.drawImage(decoded.bitmap, 0, 0);
    if (typeof decoded.bitmap.close === "function") decoded.bitmap.close();

    state.sampleTiles.set(key, {
      key,
      layerName: layer.name,
      canvas,
      ctx,
      width: canvas.width,
      height: canvas.height,
      originX: tile.left,
      originY: tile.top,
      tileX: tile.tileX,
      tileY: tile.tileY,
      encoding: sampleEncodingFor(layer),
      lastUsed: performance.now(),
    });
    pruneSampleTileCache();
    if (state.lastStatusPoint) requestAnimationFrame(() => updateStatus(state.lastStatusPoint));
  })().finally(() => state.sampleTileLoads.delete(key));

  state.sampleTileLoads.set(key, load);
  return load;
}

async function decodeSampleBitmap(blob, crop) {
  if (crop.full || crop.tile) {
    const bitmap = await createImageBitmap(blob);
    return { bitmap, sourceX: 0, sourceY: 0, width: bitmap.width, height: bitmap.height };
  }
  try {
    const bitmap = await createImageBitmap(blob, crop.left, crop.top, crop.width, crop.height);
    return { bitmap, sourceX: 0, sourceY: 0, width: bitmap.width, height: bitmap.height };
  } catch {
    const bitmap = await createImageBitmap(blob);
    return { bitmap, sourceX: crop.left, sourceY: crop.top, width: crop.width, height: crop.height };
  }
}

function sampleCropFor(layer, imageX, imageY) {
  if (layer.sample_tiles && Number.isFinite(imageX) && Number.isFinite(imageY)) {
    const tile = sampleTileFor(layer, imageX, imageY);
    if (tile) return { tile: true, ...tile };
  }
  if (layer.name === "height" || !Number.isFinite(imageX) || !Number.isFinite(imageY)) {
    return { full: true, left: 0, top: 0, width: state.imageWidth, height: state.imageHeight };
  }
  const centreX = Math.max(0, Math.min(state.imageWidth - 1, Math.floor(imageX)));
  const centreY = Math.max(0, Math.min(state.imageHeight - 1, Math.floor(imageY)));
  const half = Math.floor(SAMPLE_CROP_SIZE / 2);
  const left = Math.max(0, Math.min(Math.max(0, state.imageWidth - SAMPLE_CROP_SIZE), centreX - half));
  const top = Math.max(0, Math.min(Math.max(0, state.imageHeight - SAMPLE_CROP_SIZE), centreY - half));
  return {
    full: false,
    left,
    top,
    width: Math.min(SAMPLE_CROP_SIZE, state.imageWidth - left),
    height: Math.min(SAMPLE_CROP_SIZE, state.imageHeight - top),
  };
}

function sampleTileFor(layer, imageX, imageY) {
  if (!layer.sample_tiles || !Number.isFinite(imageX) || !Number.isFinite(imageY)) return null;
  const tileSize = Number(layer.sample_tiles.size) || DEFAULT_TILE_SIZE;
  const centreX = Math.max(0, Math.min(state.imageWidth - 1, Math.floor(imageX)));
  const centreY = Math.max(0, Math.min(state.imageHeight - 1, Math.floor(imageY)));
  const tileX = Math.floor(centreX / tileSize);
  const tileY = Math.floor(centreY / tileSize);
  const left = tileX * tileSize;
  const top = tileY * tileSize;
  return {
    left,
    top,
    width: Math.min(tileSize, state.imageWidth - left),
    height: Math.min(tileSize, state.imageHeight - top),
    tileX,
    tileY,
  };
}

function sampleTileCacheKey(layer, tileX, tileY) {
  return `${layer.name}\n${tileX},${tileY}`;
}

function sampleRegionUrl(layer, crop) {
  return assetUrl(
    String(layer.sample_tiles.template).replace("{x}", String(crop.tileX)).replace("{y}", String(crop.tileY))
  );
}

function sampleContains(sample, imageX, imageY) {
  if (!Number.isFinite(imageX) || !Number.isFinite(imageY)) return true;
  const x = Math.floor(imageX);
  const y = Math.floor(imageY);
  return x >= sample.originX && y >= sample.originY && x < sample.originX + sample.width && y < sample.originY + sample.height;
}

function releaseSample(layerName) {
  const sample = state.samples.get(layerName);
  if (sample) {
    sample.canvas.width = 0;
    sample.canvas.height = 0;
    state.samples.delete(layerName);
  }
  for (const [key, tile] of state.sampleTiles) {
    if (tile.layerName !== layerName) continue;
    tile.canvas.width = 0;
    tile.canvas.height = 0;
    state.sampleTiles.delete(key);
  }
}

function samplePixel(layerName, imageX, imageY) {
  const rgba = samplePixelRgba(layerName, imageX, imageY);
  if (!rgba) return undefined;
  const entry = state.layers.get(layerName);
  const encoding = sampleEncodingFor(entry?.layer);
  if (layerName !== "height" && !isHeightEncoding(encoding)) return rgba[0];
  const encoded = rgba[0] + rgba[1] * 256;
  return encoded === 0 ? null : encoded - 32768;
}

function samplePixelRgba(layerName, imageX, imageY) {
  const entry = state.layers.get(layerName);
  if (entry?.layer?.kind === "weather-live") {
    const live = state.liveWeather[entry.layer.live_weather_metric];
    if (!live || !Number.isFinite(imageX) || !Number.isFinite(imageY)) return undefined;
    const x = Math.max(0, Math.min(state.imageWidth - 1, Math.floor(imageX)));
    const y = Math.max(0, Math.min(state.imageHeight - 1, Math.floor(imageY)));
    const column = Math.max(0, Math.min(live.columns - 1, Math.floor(x * live.columns / Math.max(1, state.imageWidth))));
    const row = Math.max(0, Math.min(live.rows - 1, Math.floor(y * live.rows / Math.max(1, state.imageHeight))));
    const value = live.values[row * live.columns + column];
    return [value, 0, 0, 255];
  }
  if (entry?.layer?.sample_tiles) {
    const tileInfo = sampleTileFor(entry.layer, imageX, imageY);
    if (!tileInfo) return undefined;
    const key = sampleTileCacheKey(entry.layer, tileInfo.tileX, tileInfo.tileY);
    const tile = state.sampleTiles.get(key);
    if (!tile) {
      if (layerName === "height" || shouldLoadSample(entry)) loadSample(entry.layer, imageX, imageY).catch(() => undefined);
      return undefined;
    }
    tile.lastUsed = performance.now();
    const x = Math.max(0, Math.min(tile.width - 1, Math.floor(imageX) - tile.originX));
    const y = Math.max(0, Math.min(tile.height - 1, Math.floor(imageY) - tile.originY));
    return tile.ctx.getImageData(x, y, 1, 1).data;
  }

  const sample = state.samples.get(layerName);
  if (!sample || !sampleContains(sample, imageX, imageY)) {
    if (entry && (layerName === "height" || shouldLoadSample(entry))) loadSample(entry.layer, imageX, imageY).catch(() => undefined);
    return undefined;
  }
  const x = Math.max(0, Math.min(sample.width - 1, Math.floor(imageX) - sample.originX));
  const y = Math.max(0, Math.min(sample.height - 1, Math.floor(imageY) - sample.originY));
  return sample.ctx.getImageData(x, y, 1, 1).data;
}

function sampleEncodingFor(layer) {
  if (!layer) return "";
  return String(
    layer.sample_tiles?.encoding ||
    layer.browser_sample_encoding ||
    layer.sample_encoding ||
    ""
  ).toLowerCase();
}

function isHeightEncoding(encoding) {
  return String(encoding || "").includes("signed-decimetres") || String(encoding || "").includes("height");
}

function pruneSampleTileCache() {
  if (state.sampleTiles.size <= MAX_SAMPLE_TILES) return;
  const candidates = Array.from(state.sampleTiles.entries())
    .sort((a, b) => (Number(a[1].lastUsed) || 0) - (Number(b[1].lastUsed) || 0));

  for (const [key, tile] of candidates) {
    if (state.sampleTiles.size <= MAX_SAMPLE_TILES) break;
    tile.canvas.width = 0;
    tile.canvas.height = 0;
    state.sampleTiles.delete(key);
  }
}

function shouldLoadSample(entry) {
  return entry.layer.kind === "ore" || entry.enabled || isAnimalSourceLayer(entry.layer);
}

async function copyCoordinates(event) {
  const sample = sampleFromEvent(event);
  if (!sample) return;
  const minecraftY = minecraftHeightForCopy(sample);
  const text = `${sample.minecraftX} ${minecraftY} ${sample.minecraftZ}`;
  try {
    await navigator.clipboard.writeText(text);
    setStatus(`Copied Minecraft coordinates: ${text}`);
  } catch {
    setStatus(`Minecraft coordinates: ${text}`);
  }
}

function minecraftHeightForCopy(sample) {
  if (Number.isFinite(sample.minecraftHeight)) return sample.minecraftHeight;

  const displayedHeight = Number(minecraftHeightText(sample));
  if (Number.isFinite(displayedHeight)) return displayedHeight;

  const model = minecraftHeightModel();
  return Number.isFinite(model.seaLevelY) ? model.seaLevelY : 62;
}

function setStatus(message) {
  elements.status.value = message;
  elements.status.textContent = message;
}
