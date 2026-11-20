"use strict";

const {classes, care} = JSON.parse(document.getElementById("project-data").textContent);
const colors = {"Anthracnose": "#ffd371", "Powdery Mildew": "#d9baff", "Healthy": "#9affb5"};
const requestHeaders = {"X-MangoCare": "local"};
const legacyKey = "mangocare-scans";
let releaseCamera = null;

function initializeWorkspace(workspace) {
  const $ = (id) => document.getElementById(`${workspace.id}-${id}`);
  const ready = workspace.dataset.ready === "1";
  const classification = workspace.id === "classification";
  const stage = $("stage");
  const viewport = $("viewport");
  const preview = $("preview");
  const video = $("video");
  const overlay = $("boxes");
  const slider = $("confidence");
  const status = $("status");
  let photos = [];
  let selected = null;
  let mode = "files";
  let busy = false;
  let camera = null;
  let cameraGeneration = 0;
  let zoom = 1;
  let pan = [0, 0];
  let drag = null;
  let historyGeneration = 0;

  function element(tag, text, className) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
  }

  function percentage(value) {
    return value == null ? "—" : `${(value * 100).toFixed(1)}%`;
  }

  function updateControls() {
    const canScanPhotos = !busy && mode === "files" && photos.length > 0;
    $("run").disabled = !ready || !canScanPhotos;
    for (const id of ["photos", "reset", "files-mode", "camera-mode"]) $(id).disabled = busy;
    if (!classification) $("clear-history").disabled = busy;
    const showZoom = mode === "files" && Boolean(selected);
    for (const id of ["zoom-out", "zoom-in", "zoom-reset"]) $(id).disabled = !showZoom;
  }

  function transform() {
    stage.style.transform = `translate(${pan[0]}px,${pan[1]}px) scale(${zoom})`;
  }
  function fit() {
    zoom = 1;
    pan = [0, 0];
    transform();
  }
  function changeZoom(delta) {
    zoom = Math.max(1, Math.min(4, zoom + delta));
    if (zoom === 1) fit();
    else transform();
  }

  function paint(detections = [], source = preview) {
    const ctx = overlay.getContext("2d");
    const width = source.videoWidth || source.naturalWidth || 0;
    const height = source.videoHeight || source.naturalHeight || 0;
    overlay.width = width;
    overlay.height = height;
    if (!width || !height) return;
    const textSize = Math.max(14, Math.round(width / 40));
    ctx.font = `bold ${textSize}px system-ui`;
    ctx.lineWidth = Math.max(2, width / 320);
    for (const item of detections) {
      const [left, top, right, bottom] = item.box;
      const x = left * width;
      const y = top * height;
      const label = `${item.label} ${percentage(item.confidence)}`;
      const tagWidth = ctx.measureText(label).width + 10;
      const tagX = Math.max(0, Math.min(x, width - tagWidth));
      const tagY = Math.max(0, y - textSize - 6);
      ctx.strokeStyle = colors[item.label];
      ctx.strokeRect(x, y, (right - left) * width, (bottom - top) * height);
      ctx.fillStyle = colors[item.label];
      ctx.fillRect(tagX, tagY, tagWidth, textSize + 6);
      ctx.fillStyle = "#10271d";
      ctx.fillText(label, tagX + 5, tagY + textSize);
    }
  }

  function showPhoto(photo) {
    selected = photo;
    fit();
    stage.hidden = !photo;
    $("empty").hidden = Boolean(photo);
    preview.hidden = !photo;
    video.hidden = true;
    if (overlay) overlay.width = 0;
    if (photo) {
      if (!classification) preview.onload = () => paint(photo.result?.detections);
      preview.src = photo.result ? `data:image/jpeg;base64,${photo.result.image}` : photo.url;
      if (!classification && preview.complete) paint(photo.result?.detections);
    } else {
      preview.removeAttribute("src");
    }
    updateControls();
  }

  function renderQueue() {
    $("queue").replaceChildren();
    for (const photo of photos) {
      const item = element("li");
      const button = element("button", photo.file.name);
      button.type = "button";
      button.addEventListener("click", () => showPhoto(photo));
      item.append(button);
      $("queue").append(item);
    }
  }

  function addFiles(files) {
    if (busy) return [];
    const valid = [...files].filter((file) => file.size > 0 && file.size <= 11.9 * 1024 * 1024 &&
      /\.(jpe?g|png|webp)$/i.test(file.name));
    const accepted = valid.slice(0, 12 - photos.length);
    const added = accepted.map((file) => ({file, url: URL.createObjectURL(file), result: null}));
    photos.push(...added);
    status.textContent = added.length === files.length ? "" : "Some photos were skipped. Use up to 12 valid photos, each under 12 MB.";
    renderQueue();
    if (added.length) showPhoto(added[0]);
    return added;
  }

  function setConfidence(value) {
    slider.value = value;
    $("confidence-value").textContent = `${slider.value}%`;
    for (const preset of workspace.querySelectorAll("[data-confidence]")) {
      preset.setAttribute("aria-pressed", String(preset.dataset.confidence === slider.value));
    }
  }

  async function readResponse(response) {
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "MangoCare could not complete the request.");
    return data;
  }

  async function scan(file, threshold, live = false, signal) {
    const form = new FormData();
    form.append("photo", file, file.name || "webcam.jpg");
    form.append("threshold", Number(threshold) / 100);
    if (live) form.append("live", "1");
    return readResponse(await fetch("/api/detect", {method: "POST", headers: requestHeaders, body: form, signal}));
  }

  async function classifyPhoto(file) {
    const form = new FormData();
    form.append("photo", file);
    return readResponse(await fetch("/api/classify", {method: "POST", headers: requestHeaders, body: form}));
  }

  function reportLink(recordId) {
    const link = element("a", "Download PDF");
    link.href = `/api/report/${recordId}`;
    return link;
  }

  function appendCare(container, label) {
    const {description, ...sections} = care[label];
    container.append(element("p", description));
    for (const [heading, tips] of Object.entries(sections)) {
      const list = element("ul");
      for (const tip of tips) list.append(element("li", tip));
      container.append(element("h4", heading), list);
    }
  }

  function resultCard(photo, error) {
    const card = element("article", undefined, "card result-card");
    const body = element("div", undefined, "result-body");
    body.append(element("h4", photo.file.name, "result-filename"));
    if (!error) {
      const thumbnail = element("img", undefined, "result-thumbnail");
      thumbnail.src = photo.url;
      thumbnail.alt = "";
      thumbnail.loading = "lazy";
      card.append(thumbnail);
    }
    card.append(body);
    $("results").append(card);
    return body;
  }

  function appendResult(photo) {
    const body = resultCard(photo, photo.error);
    if (photo.error) {
      body.append(element("p", photo.error));
      return;
    }
    const {detections, id} = photo.result;
    const count = detections.length;
    body.append(element("p", `${count} detected ${count === 1 ? "object" : "objects"}`, "result-title"));
    const counts = element("div", undefined, "result-tags");
    for (const label of classes) {
      const total = detections.filter((item) => item.label === label).length;
      if (!total) continue;
      const tag = element("span", `${label} · ${total}`, "result-tag");
      tag.dataset.label = label;
      counts.append(tag);
    }
    if (count) body.append(counts);
    else body.append(element("p", "No matches at this threshold. Try a sharper photo or lower the threshold.", "muted"));
    const actions = element("div", undefined, "actions result-actions");
    const inspect = element("button", "View boxes");
    inspect.addEventListener("click", () => {
      showPhoto(photo);
      viewport.scrollIntoView({block: "center"});
    });
    const report = reportLink(id);
    report.className = "button quiet";
    actions.append(inspect, report);
    body.append(actions);
    for (const label of new Set(detections.map((item) => item.label))) {
      const detail = element("details", undefined, "result-care");
      detail.append(element("summary", `${label} details`));
      const values = detections.filter((item) => item.label === label).map((item) => percentage(item.confidence));
      detail.append(element("p", "Confidence · " + values.join(", ")));
      appendCare(detail, label);
      body.append(detail);
    }
  }

  function appendClassification(photo, prediction, error) {
    const body = resultCard(photo, error);
    if (error) {
      body.append(element("p", error));
      return;
    }
    body.append(element("p", `Prediction · ${prediction.label}`, "result-title"));
    const scores = element("div", undefined, "result-scores");
    for (const [label, score] of Object.entries(prediction.scores).sort((a, b) => b[1] - a[1])) {
      const row = element("div", undefined, "result-score");
      const track = element("span", undefined, "score-track");
      track.setAttribute("aria-hidden", "true");
      const fill = element("span", undefined, "score-fill");
      fill.dataset.label = label;
      fill.style.width = `${score * 100}%`;
      track.append(fill);
      row.append(element("span", label), element("strong", percentage(score)), track);
      scores.append(row);
    }
    const detail = element("details", undefined, "result-care");
    detail.append(element("summary", prediction.label === "Healthy" ? "Leaf care & precautions" : "Precautions & treatment"));
    appendCare(detail, prediction.label);
    body.append(scores, detail);
  }

  async function runBatch() {
    if (busy || !photos.length) return;
    busy = true;
    updateControls();
    const threshold = slider.value;
    $("results").replaceChildren(element("h3", "Detection results"));
    viewport.classList.add("scanning");
    for (const [index, photo] of photos.entries()) {
      photo.error = null;
      photo.result = null;
      showPhoto(photo);
      status.textContent = `Scanning ${index + 1} of ${photos.length}…`;
      try {
        photo.result = await scan(photo.file, threshold);
      } catch (error) {
        photo.error = error.message;
      }
      appendResult(photo);
    }
    viewport.classList.remove("scanning");
    status.textContent = `Finished ${photos.length} scan${photos.length === 1 ? "" : "s"}.`;
    busy = false;
    showPhoto(photos.find((photo) => photo.result) || photos[0]);
    refreshHistory();
  }

  async function runClassification(batch = photos) {
    if (!ready || busy || !batch.length) return;
    busy = true;
    updateControls();
    $("results").replaceChildren(element("h3", "Classification results"));
    for (const [index, photo] of batch.entries()) {
      status.textContent = `Classifying ${index + 1} of ${batch.length}…`;
      try {
        appendClassification(photo, await classifyPhoto(photo.file));
      } catch (error) {
        appendClassification(photo, null, error.message);
      }
    }
    status.textContent = `Finished classifying ${batch.length} photo${batch.length === 1 ? "" : "s"}.`;
    busy = false;
    updateControls();
  }

  function stopCamera() {
    if (releaseCamera === stopCamera) releaseCamera = null;
    cameraGeneration += 1;
    if (camera) camera.getTracks().forEach((track) => track.stop());
    camera = null;
    video.srcObject = null;
    $("fps").textContent = "Camera off";
    if (!classification) {
      overlay.width = 0;
      $("live-results").hidden = true;
    }
    $("start-camera").disabled = false;
    $("stop-camera").disabled = true;
    $("capture").disabled = true;
    if (mode === "camera") {
      stage.hidden = true;
      $("empty").hidden = false;
      $("empty").textContent = "Start the camera to see its preview.";
    }
  }

  function chooseMode(next) {
    if (mode === next) return;
    stopCamera();
    mode = next;
    $("files-panel").hidden = mode !== "files";
    $("camera-panel").hidden = mode !== "camera";
    $("files-mode").setAttribute("aria-pressed", String(mode === "files"));
    $("camera-mode").setAttribute("aria-pressed", String(mode === "camera"));
    status.textContent = "";
    if (mode === "files") {
      $("empty").textContent = "Choose a photo to see its preview.";
      showPhoto(selected);
    } else {
      $("empty").textContent = "Start the camera to see its preview.";
      $("empty").hidden = false;
      stage.hidden = true;
      fit();
    }
    updateControls();
  }

  const frameCanvas = document.createElement("canvas");
  function frame(maxWidth) {
    frameCanvas.width = Math.min(maxWidth, video.videoWidth);
    frameCanvas.height = Math.round(video.videoHeight * frameCanvas.width / video.videoWidth);
    frameCanvas.getContext("2d").drawImage(video, 0, 0, frameCanvas.width, frameCanvas.height);
    return new Promise((resolve) => frameCanvas.toBlob(resolve, "image/jpeg", .86));
  }

  async function liveLoop(generation) {
    let last = performance.now();
    while (camera && cameraGeneration === generation) {
      try {
        const blob = await frame(640);
        if (!blob || cameraGeneration !== generation) break;
        const data = await scan(blob, slider.value, true);
        if (cameraGeneration !== generation) break;
        paint(data.detections, video);
        const now = performance.now();
        $("fps").textContent = `${(1000 / Math.max(1, now - last)).toFixed(1)} detection FPS`;
        last = now;
        $("live-results").hidden = false;
        $("live-results").textContent = data.detections.map((item) =>
          `${item.label} ${percentage(item.confidence)}`).join(" · ") || "No current matches";
      } catch (error) {
        if (cameraGeneration === generation) {
          status.textContent = error.message;
          stopCamera();
        }
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 120));
    }
  }

  async function startCamera() {
    if (releaseCamera === stopCamera || mode !== "camera") return;
    releaseCamera?.();
    releaseCamera = stopCamera;
    const generation = ++cameraGeneration;
    $("start-camera").disabled = true;
    $("stop-camera").disabled = false;
    status.textContent = "Waiting for camera access…";
    try {
      if (!navigator.mediaDevices?.getUserMedia) throw new Error("Camera access is unavailable in this browser.");
      const stream = await navigator.mediaDevices.getUserMedia({
        video: {width: {ideal: 960}, height: {ideal: 720}}, audio: false,
      });
      if (generation !== cameraGeneration) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }
      camera = stream;
      for (const track of stream.getVideoTracks()) {
        track.addEventListener("ended", () => {
          if (generation !== cameraGeneration) return;
          status.textContent = "The camera disconnected.";
          stopCamera();
        });
      }
      video.srcObject = stream;
      preview.hidden = true;
      video.hidden = false;
      stage.hidden = false;
      $("empty").hidden = true;
      await video.play();
      if (generation !== cameraGeneration) return;
      $("capture").disabled = classification && !ready;
      status.textContent = ready ? "" : "Camera preview only until the checkpoint is available.";
      $("fps").textContent = classification ? "Camera on. Capture one leaf to classify it." : "Camera on";
      if (ready && !classification) liveLoop(generation);
    } catch (error) {
      if (generation !== cameraGeneration) return;
      status.textContent = error.name === "NotAllowedError" ? "Camera access was denied. Allow camera access in your browser and retry." :
        error.name === "NotFoundError" ? "No webcam was found." : error.message;
      stopCamera();
    }
  }

  function renderLegacy() {
    const list = $("legacy-list");
    list.replaceChildren();
    $("legacy").hidden = true;
    try {
      const entries = JSON.parse(localStorage.getItem(legacyKey) || "[]");
      if (!Array.isArray(entries) || !entries.length) return;
      $("legacy").hidden = false;
      for (const entry of entries) list.append(element("li", `${new Date(entry.date).toLocaleString()} · ${entry.labels}`));
    } catch {}
  }

  async function refreshHistory(method = "GET") {
    const generation = ++historyGeneration;
    const list = $("history-list");
    try {
      const data = await readResponse(await fetch("/api/history", {method, headers: requestHeaders, cache: "no-store"}));
      if (generation !== historyGeneration) return;
      list.replaceChildren();
      if (!data.records.length) list.append(element("p", "No scans saved yet."));
      for (const record of data.records) {
        const row = element("div", undefined, "history-line");
        const items = record.detections.map((item) => `${item.label} ${percentage(item.confidence)}`);
        row.append(element("span", `${new Date(record.time).toLocaleString()} · ${items.join(", ") || "No matches"}`), reportLink(record.id));
        list.append(row);
      }
      $("overall").textContent = data.average == null ? "No confidence scores yet." : `Overall average ${percentage(data.average)}`;
      $("statistics-list").replaceChildren(...classes.map((label) => {
        const metric = data.statistics[label];
        const node = element("div");
        node.append(element("strong", label), element("div", percentage(metric.average)), element("small", `${metric.count} detected objects`));
        return node;
      }));
      if (method === "DELETE") {
        try { localStorage.removeItem(legacyKey); } catch {}
      }
      renderLegacy();
    } catch (error) {
      if (generation === historyGeneration) list.replaceChildren(element("p", error.message));
    }
  }

  $("photos").addEventListener("change", (event) => {
    addFiles(event.target.files);
    event.target.value = "";
  });
  const drop = $("drop");
  drop.addEventListener("dragover", (event) => {
    event.preventDefault();
    if (!busy) drop.classList.add("over");
  });
  drop.addEventListener("dragleave", () => drop.classList.remove("over"));
  drop.addEventListener("drop", (event) => {
    event.preventDefault();
    drop.classList.remove("over");
    addFiles(event.dataTransfer.files);
  });
  $("run").addEventListener("click", classification ? () => runClassification() : runBatch);
  $("reset").addEventListener("click", () => {
    for (const photo of photos) URL.revokeObjectURL(photo.url);
    photos = [];
    selected = null;
    $("results").replaceChildren();
    renderQueue();
    chooseMode("files");
    status.textContent = "";
    showPhoto(null);
  });
  $("zoom-in").addEventListener("click", () => changeZoom(.25));
  $("zoom-out").addEventListener("click", () => changeZoom(-.25));
  $("zoom-reset").addEventListener("click", fit);
  viewport.addEventListener("pointerdown", (event) => {
    if (zoom <= 1 || mode !== "files") return;
    event.preventDefault();
    drag = [event.clientX - pan[0], event.clientY - pan[1]];
    viewport.setPointerCapture(event.pointerId);
  });
  viewport.addEventListener("pointermove", (event) => {
    if (!drag) return;
    pan = [event.clientX - drag[0], event.clientY - drag[1]];
    transform();
  });
  for (const name of ["pointerup", "pointercancel", "lostpointercapture"]) {
    viewport.addEventListener(name, () => { drag = null; });
  }
  $("files-mode").addEventListener("click", () => chooseMode("files"));
  $("camera-mode").addEventListener("click", () => chooseMode("camera"));
  $("start-camera").addEventListener("click", startCamera);
  $("stop-camera").addEventListener("click", () => {
    stopCamera();
    status.textContent = "";
  });
  $("capture").addEventListener("click", async () => {
    if (!camera) return;
    if (photos.length === 12) {
      status.textContent = "The photo queue is full. Select Reset before capturing more photos.";
      return;
    }
    const generation = cameraGeneration;
    const blob = await frame(1280);
    if (!blob || generation !== cameraGeneration) return;
    chooseMode("files");
    const captured = addFiles([new File([blob], "Webcam capture.jpg", {type: "image/jpeg"})]);
    if (classification) await runClassification(captured);
  });
  if (!classification) {
    slider.addEventListener("input", () => setConfidence(slider.value));
    for (const preset of workspace.querySelectorAll("[data-confidence]")) {
      preset.addEventListener("click", () => setConfidence(preset.dataset.confidence));
    }
    $("clear-history").addEventListener("click", () => {
      if (confirm("Permanently delete all saved detection history?")) refreshHistory("DELETE");
    });
    setConfidence(25);
    refreshHistory();
  }
}

for (const workspace of document.querySelectorAll("[data-scan-tool]")) {
  initializeWorkspace(workspace);
}
addEventListener("pagehide", () => releaseCamera?.());
document.addEventListener("visibilitychange", () => {
  if (document.hidden) releaseCamera?.();
});
