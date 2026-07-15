(function initHoverRenderMath(globalScope) {
  function fitZoom(viewportWidth, viewportHeight, imageWidth, imageHeight, padding = 0) {
    const width = Number(viewportWidth) || 0;
    const height = Number(viewportHeight) || 0;
    const imageW = Math.max(1, Number(imageWidth) || 1);
    const imageH = Math.max(1, Number(imageHeight) || 1);
    const inset = Math.max(0, Number(padding) || 0) * 2;
    const innerWidth = Math.max(1, width - inset);
    const innerHeight = Math.max(1, height - inset);
    return Math.min(1, innerWidth / imageW, innerHeight / imageH);
  }

  function clampAxisOffset(offset, viewportSize, scaledImageSize) {
    const viewport = Math.max(0, Number(viewportSize) || 0);
    const scaled = Math.max(0, Number(scaledImageSize) || 0);
    const minOffset = Math.min(0, viewport - scaled);
    const maxOffset = Math.max(0, viewport - scaled);
    const desired = Number.isFinite(offset) ? Number(offset) : 0;
    return Math.min(maxOffset, Math.max(minOffset, desired));
  }

  function compositeStraightAlpha(backgroundRgb, overlayRgba) {
    const alpha = Math.max(0, Math.min(255, Number(overlayRgba?.[3]) || 0)) / 255;
    const inverse = 1 - alpha;
    return [
      Math.round((Number(backgroundRgb?.[0]) || 0) * inverse + (Number(overlayRgba?.[0]) || 0) * alpha),
      Math.round((Number(backgroundRgb?.[1]) || 0) * inverse + (Number(overlayRgba?.[1]) || 0) * alpha),
      Math.round((Number(backgroundRgb?.[2]) || 0) * inverse + (Number(overlayRgba?.[2]) || 0) * alpha),
    ];
  }

  const api = {
    clampAxisOffset,
    compositeStraightAlpha,
    fitZoom,
  };

  globalScope.HoverRenderMath = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
