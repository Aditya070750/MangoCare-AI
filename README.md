# MangoCare AI for Windows

See [HOW_TO_USE.txt](HOW_TO_USE.txt) for the quick-start and scan instructions.

MangoCare runs locally at [127.0.0.1:8765](http://127.0.0.1:8765/). It uses a YOLO11n object detector and a YOLO11n image classifier.

## Install and start

Open PowerShell in the application folder. Python 3.13 was used to verify this setup.

```powershell
python -m pip install -r requirements.txt
python .\local_detection.py
```

Installation may need internet access. Once dependencies are installed, the dashboard, inference, scan history, statistics, and PDF generation work locally. Leave PowerShell running and open the localhost address in Chrome or Edge. If the address is already in use, close the earlier MangoCare terminal before starting another copy.

The application expects `best.pt` beside `local_detection.py`. To use the same trained checkpoint from a different location, set `MANGOCARE_WEIGHTS` to its absolute path before startup. No pretrained substitute is downloaded. The checkpoint is loaded on the first scan.

The classifier expects `mangocare_classifier.pt` in the same folder. Set `MANGOCARE_CLASSIFIER_WEIGHTS` to use a different location. The classifier loads on the first classification request and runs on CPU with a 224-pixel input size.

## Detection

- Choose or drag up to 12 JPG, PNG, or WebP photos. Review the queue and click **Run Detection**. Batch photos are processed one at a time. Select a photo in the queue or use a result card to view its boxes.
- Click **Webcam → Start Camera** and allow camera access in your browser. Frames are processed one at a time at a 416-pixel inference size. The FPS figure measures completed detections per second. **Capture Photo** adds a still to the photo queue for a saved scan.
- **Light** uses 65% minimum confidence, **Medium** 45%, **High** 25%, and **Ultra** 10%. The slider accepts 1–99%. Lower filters accept weaker predictions. A missed region can remain missed at every threshold.
- Use **+** and **−** to zoom. Drag the zoomed preview to pan, or click **Fit** to reset its position.
- **Detection history** stores timestamps, class labels, and scores in `mangocare.sqlite3`. Live webcam frames are transient. The dashboard displays earlier browser-only history separately where it is available. A confirmed **Clear history** removes both the database history and that browser’s saved entries.
- Use **Download PDF** on a result or history entry. The report is generated in memory from the saved detections.

## Classification

Scroll to **Classification** on the home page. Choose photos that each show one mango leaf and click **Classify Photos**, or choose **Webcam → Start Camera → Capture Photo** to classify one still image. The classifier returns one label per photo and scores for class IDs 0 Anthracnose and 1 Healthy. It cannot identify Powdery Mildew. Results are visible for the current session. Use fresh labeled photos to measure independent accuracy because the final checkpoint trained on all 91 prepared photos.

## Project structure

| File or directory | Role |
|---|---|
| `local_detection.py` | Flask routes, localhost startup and request validation |
| `inference.py` | Photo preprocessing, checkpoint validation and YOLO prediction |
| `guidance.py` | Shared mango care guidance |
| `storage.py` | SQLite history and confidence summaries |
| `reports.py` | PDF report generation |
| `templates/`, `static/` | Homepage, dashboard, styling, browser interactions and local hero photo |
| `best.pt` | Custom YOLO11n detection checkpoint |
| `mangocare_classifier.pt` | Binary YOLO11n image classification checkpoint |
| `MangoCare_YOLO_Training.ipynb`, `train_yolo.py`, `prepare_yolo.py` | Training and dataset preparation assets |

## Inference and Android handoff

Class IDs are 0 Anthracnose, 1 Powdery Mildew, and 2 Healthy. The web app EXIF-orients photos, converts to RGB, and caps the longest side at 1600 before Ultralytics preprocessing. Ultralytics letterboxes and normalizes inputs, runs YOLO, filters by confidence, and applies non-maximum suppression. Still inference uses a target size of 640 and live inference uses 416. The web API returns four normalized coordinates in `[left, top, right, bottom]` order relative to the oriented image. Counts refer to detected objects because the dataset does not establish that every box represents a distinct leaf.

The earlier ONNX export produced `[1,7,8400]` at 640 pixels, with four box channels and three class-score channels. For Android, inspect the actual LiteRT tensor metadata before selecting layout. Match resize, padding and normalization, decode box centers and sizes, apply class-wise NMS, and remap coordinates to the original photo. Validate the exported model against this Python detector on shared examples before using it in the Android app.

The inspected MX250 environment runs CPU-only PyTorch. GPU selection requires a successful CUDA prediction and falls back to CPU on CUDA inference errors. Actual GPU throughput depends on the installed build and available memory.

In the annotation spot check, boxes varied from small regions to much of a leaf, and some were visibly offset. This can contribute to missed or misplaced predictions.

## Training and distribution

The optional detector scripts and notebooks are included at the top level. The
`Classification/` folder includes the classifier training notebook, preparation
and prediction scripts, and training metadata. Training datasets are separate
inputs. The included checkpoints are ready for local inference.

See the [Ultralytics license guidance](https://www.ultralytics.com/license) before
publishing the YOLO models and source code.
