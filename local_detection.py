"""Serve MangoCare privately on the laptop."""

from base64 import b64encode
from io import BytesIO
from math import isfinite

from flask import Flask, jsonify, redirect, render_template, request, send_file, url_for

import storage
from guidance import CARE, NOTE, SOURCE
from inference import CLASSES, PRESETS, ROOT, Classifier, Detector, read_photo

app = Flask(__name__)
app.config.update(
    MAX_CONTENT_LENGTH=12 * 1024 * 1024,
    HISTORY_PATH=ROOT / "mangocare.sqlite3",
    TRUSTED_HOSTS=["127.0.0.1", "localhost"],
)
detector = Detector()
classifier = Classifier()


def uploaded_photo():
    upload = request.files.get("photo")
    if upload is None:
        raise ValueError("Choose a photo first.")
    return read_photo(upload.stream)


@app.before_request
def require_local_client():
    if request.method in {"POST", "DELETE"} and request.headers.get("X-MangoCare") != "local":
        return jsonify(error="Open the MangoCare dashboard to make this request."), 403


@app.errorhandler(413)
def too_large(_error):
    return jsonify(error="Choose a photo under 12 MB."), 413


@app.get("/")
def home():
    return render_template("home.html", detection_ready=detector.weights.is_file(),
                           classification_ready=classifier.weights.is_file(),
                           classes=list(CLASSES.values()), presets=PRESETS, care=CARE, note=NOTE, source=SOURCE)


@app.get("/dashboard", defaults={"classification": False})
@app.get("/classification", defaults={"classification": True})
def dashboard(classification):
    section = "classification" if classification else None
    return redirect(url_for("home", _anchor=section))


@app.post("/api/detect")
def detect():
    try:
        threshold = float(request.form.get("threshold", ".25"))
        if not isfinite(threshold) or not .01 <= threshold <= .99:
            raise ValueError("Confidence must be between 1% and 99%.")
        image = uploaded_photo()
    except ValueError as exc:
        return jsonify(error=str(exc)), 400

    live = request.form.get("live") == "1"
    try:
        detections = detector.predict(image, threshold, live=live)
        if live:
            return jsonify(detections=detections, device=detector.device)
        buffer = BytesIO()
        image.save(buffer, "JPEG", quality=88)
        scan_id = storage.save_scan(app.config["HISTORY_PATH"], detections)
        return jsonify(id=scan_id, detections=detections, device=detector.device,
                       image=b64encode(buffer.getvalue()).decode("ascii"))
    except FileNotFoundError as exc:
        return jsonify(error=str(exc)), 503
    except Exception:
        app.logger.exception("MangoCare inference failed")
        return jsonify(error="The model could not complete this scan. Check the server log for details."), 500


@app.post("/api/classify")
def classify():
    try:
        image = uploaded_photo()
    except ValueError as exc:
        return jsonify(error=str(exc)), 400

    try:
        return jsonify(classifier.predict(image))
    except FileNotFoundError as exc:
        return jsonify(error=str(exc)), 503
    except Exception:
        app.logger.exception("MangoCare classification failed")
        return jsonify(error="The classifier could not complete this scan. Check the server log for details."), 500


@app.route("/api/history", methods=["GET", "DELETE"])
def history():
    path = app.config["HISTORY_PATH"]
    if request.method == "DELETE":
        storage.clear(path)
    records = storage.records(path)
    detections = [item for record in records for item in record["detections"]]
    return jsonify(records=records, statistics=storage.summarize(detections, CLASSES.values()),
                   average=sum(item["confidence"] for item in detections) / len(detections) if detections else None)


@app.get("/api/report/<int:scan_id>")
def report(scan_id):
    records = storage.records(app.config["HISTORY_PATH"], scan_id)
    if not records:
        return jsonify(error="This scan is no longer in history."), 404
    try:
        from reports import make_report
    except ImportError:
        return jsonify(error="Install the project requirements to enable PDF reports."), 503
    record = records[0]
    summary = storage.summarize(record["detections"], CLASSES.values())
    return send_file(make_report(record, summary), mimetype="application/pdf", as_attachment=True,
                     download_name=f"mangocare-scan-{scan_id}.pdf")


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=8765)
