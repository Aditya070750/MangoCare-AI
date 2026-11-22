"use strict";

window.mangocareBrowser = (() => {
  const staticDir = new URL(".", document.currentScript.src);
  const liteRtPackage = "https://cdn.jsdelivr.net/npm/@litertjs/core@2.6.0";
  const labels = ["Anthracnose", "Powdery Mildew", "Healthy"];
  const classifierLabels = ["Anthracnose", "Healthy"];
  const historyKey = "mangocare-pages-history";
  let runtime;
  const models = new Map();

  async function loadRuntime() {
    const api = await import(`${liteRtPackage}/+esm`);
    await api.loadLiteRt(`${liteRtPackage}/wasm/`);
    return api;
  }

  async function getModel(name) {
    runtime ??= loadRuntime().catch((error) => {
      runtime = undefined;
      throw error;
    });
    const api = await runtime;
    if (!models.has(name)) {
      const url = new URL(name, staticDir).href;
      models.set(name, api.loadAndCompile(url, {accelerator: "wasm"}).catch((error) => {
        models.delete(name);
        throw error;
      }));
    }
    return {model: await models.get(name), Tensor: api.Tensor};
  }

  function canvas(width, height) {
    const result = document.createElement("canvas");
    result.width = width;
    result.height = height;
    return result;
  }

  async function readPhoto(file) {
    const message = "Choose a valid JPG, PNG, or WebP image up to 24 megapixels and 12 MB.";
    if (file.size > 12 * 1024 * 1024 || !/^image\/(jpeg|png|webp)$/.test(file.type)) throw new Error(message);
    let bitmap;
    try {
      bitmap = await createImageBitmap(file, {imageOrientation: "from-image"});
      if (bitmap.width * bitmap.height > 24_000_000) throw new Error(message);
      const ratio = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height));
      const photo = canvas(Math.max(1, Math.round(bitmap.width * ratio)), Math.max(1, Math.round(bitmap.height * ratio)));
      const context = photo.getContext("2d", {alpha: false});
      context.imageSmoothingQuality = "high";
      context.drawImage(bitmap, 0, 0, photo.width, photo.height);
      return photo;
    } catch {
      throw new Error(message);
    } finally {
      bitmap?.close();
    }
  }

  function prepare(photo, size, classify) {
    const ratio = classify ? size / Math.min(photo.width, photo.height) : size / Math.max(photo.width, photo.height);
    const width = Math.max(1, Math.round(photo.width * ratio));
    const height = Math.max(1, Math.round(photo.height * ratio));
    const x = Math.floor((size - width) / 2);
    const y = Math.floor((size - height) / 2);
    const context = canvas(size, size).getContext("2d", {alpha: false, willReadFrequently: true});
    context.fillStyle = "#727272";
    context.fillRect(0, 0, size, size);
    context.drawImage(photo, x, y, width, height);
    const rgba = context.getImageData(0, 0, size, size).data;
    const rgb = new Float32Array(size * size * 3);
    for (let i = 0, j = 0; i < rgba.length; i += 4, j += 3) {
      rgb[j] = rgba[i] / 255;
      rgb[j + 1] = rgba[i + 1] / 255;
      rgb[j + 2] = rgba[i + 2] / 255;
    }
    return {rgb, x, y, width, height};
  }

  async function predict(name, size, pixels, signal) {
    const {model, Tensor} = await getModel(name);
    signal?.throwIfAborted();
    const input = new Tensor(pixels, [1, size, size, 3]);
    let output;
    try {
      output = await model.run(input);
      return new Float32Array(await output[0].toTypedArray());
    } finally {
      output?.forEach((tensor) => tensor.delete());
      input.delete();
    }
  }

  function iou(a, b) {
    const width = Math.max(0, Math.min(a[2], b[2]) - Math.max(a[0], b[0]));
    const height = Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
    const intersection = width * height;
    const area = (box) => (box[2] - box[0]) * (box[3] - box[1]);
    return intersection / (area(a) + area(b) - intersection);
  }

  function decode(values, prepared, threshold) {
    const size = 640;
    const anchors = 8400;
    const detections = [];
    for (let i = 0; i < anchors; i += 1) {
      let best = 0;
      for (let c = 1; c < labels.length; c += 1) {
        if (values[(4 + c) * anchors + i] > values[(4 + best) * anchors + i]) best = c;
      }
      const confidence = values[(4 + best) * anchors + i];
      if (confidence <= threshold) continue;
      const x = values[i] * size;
      const y = values[anchors + i] * size;
      const halfWidth = values[2 * anchors + i] * size / 2;
      const halfHeight = values[3 * anchors + i] * size / 2;
      if (halfWidth <= 0 || halfHeight <= 0) continue;
      detections.push({label: labels[best], confidence, box: [x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight]});
    }
    detections.sort((a, b) => b.confidence - a.confidence);
    const kept = [];
    for (const detection of detections) {
      if (kept.some((item) => item.label === detection.label && iou(item.box, detection.box) > 0.7)) continue;
      kept.push(detection);
      if (kept.length === 300) break;
    }
    const clamp = (number) => Math.min(1, Math.max(0, number));
    return kept.map(({label, confidence, box: [left, top, right, bottom]}) => ({
      label, confidence,
      box: [
        clamp((left - prepared.x) / prepared.width), clamp((top - prepared.y) / prepared.height),
        clamp((right - prepared.x) / prepared.width), clamp((bottom - prepared.y) / prepared.height),
      ],
    }));
  }

  function records() {
    return JSON.parse(localStorage.getItem(historyKey) || "[]");
  }

  async function detect(file, threshold, live, signal) {
    const size = 640;
    const photo = await readPhoto(file);
    const prepared = prepare(photo, size, false);
    const raw = await predict("mangocare_yolo11n_float32.tflite", size, prepared.rgb, signal);
    signal?.throwIfAborted();
    const detections = decode(raw, prepared, threshold);
    if (live) return {detections, device: "browser"};
    const id = crypto.randomUUID();
    const metadata = detections.map(({label, confidence}) => ({label, confidence}));
    localStorage.setItem(historyKey, JSON.stringify([{id, time: new Date().toISOString(), detections: metadata}, ...records()]));
    return {id, detections, image: photo.toDataURL("image/jpeg", 0.88).split(",")[1], device: "browser"};
  }

  async function classify(file) {
    const size = 224;
    const {rgb} = prepare(await readPhoto(file), size, true);
    const scores = await predict("mangocare_classifier_float32.tflite", size, rgb);
    return {
      label: classifierLabels[Number(scores[1] > scores[0])],
      scores: Object.fromEntries(classifierLabels.map((label, index) => [label, scores[index]])),
    };
  }

  function history(clear) {
    if (clear) localStorage.removeItem(historyKey);
    const saved = records();
    const detected = saved.flatMap((record) => record.detections);
    const average = (items) => items.length ? items.reduce((total, item) => total + item.confidence, 0) / items.length : null;
    const statistics = Object.fromEntries(labels.map((label) => {
      const items = detected.filter((item) => item.label === label);
      return [label, {count: items.length, average: average(items)}];
    }));
    return {records: saved, average: average(detected), statistics};
  }

  async function downloadReport(id) {
    const record = records().find((entry) => entry.id === id);
    if (!record) throw new Error("This scan is no longer in history.");
    const {jsPDF} = await import("https://cdn.jsdelivr.net/npm/jspdf@4.2.1/+esm");
    const pdf = new jsPDF();
    pdf.setFontSize(20);
    pdf.text("MangoCare AI detection report", 18, 22);
    pdf.setFontSize(11);
    pdf.text(new Date(record.time).toLocaleString(), 18, 33);
    const count = record.detections.length;
    pdf.text(`${count} detected ${count === 1 ? "object" : "objects"}`, 18, 44);
    let y = 56;
    for (const {label, confidence} of record.detections) {
      if (y > 275) {
        pdf.addPage();
        y = 22;
      }
      pdf.text(`${label}: ${(confidence * 100).toFixed(1)}% model confidence`, 18, y);
      y += 7;
    }
    if (y > 268) {
      pdf.addPage();
      y = 22;
    }
    pdf.text("Model confidence is a score and does not measure correctness.", 18, y + 8);
    pdf.save(`mangocare-scan-${id}.pdf`);
  }

  return {detect, classify, history, downloadReport};
})();
