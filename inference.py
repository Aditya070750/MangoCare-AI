"""Local inference with the custom MangoCare checkpoints."""

import os
from pathlib import Path
from threading import Lock

from PIL import Image, ImageOps, UnidentifiedImageError

ROOT = Path(__file__).resolve().parent
CLASSES = {0: "Anthracnose", 1: "Powdery Mildew", 2: "Healthy"}
CLASSIFIER_LABELS = {0: "Anthracnose", 1: "Healthy"}
PRESETS = {"Light": .65, "Medium": .45, "High": .25, "Ultra": .10}


def read_photo(stream):
    try:
        with Image.open(stream) as source:
            if source.format not in {"JPEG", "PNG", "WEBP"} or source.width * source.height > 24_000_000:
                raise ValueError
            image = ImageOps.exif_transpose(source).convert("RGB")
            image.thumbnail((1600, 1600), Image.Resampling.LANCZOS)
            return image
    except (OSError, ValueError, UnidentifiedImageError, Image.DecompressionBombError) as exc:
        raise ValueError("Choose a valid JPG, PNG, or WebP image up to 24 megapixels.") from exc


class Detector:
    def __init__(self, weights=None):
        configured = Path(weights or os.environ.get("MANGOCARE_WEIGHTS", "best.pt"))
        self.weights = ROOT / configured
        self.model = None
        self.device = "cpu"
        self.lock = Lock()

    def predict(self, image, threshold, *, live=False):
        if not self.weights.is_file():
            raise FileNotFoundError(f"MangoCare checkpoint is missing: {self.weights}")
        size = 416 if live else 640
        with self.lock:
            if self.model is None:
                import torch
                from ultralytics import YOLO

                model = YOLO(str(self.weights), task="detect")
                if model.task != "detect" or model.names != CLASSES:
                    raise ValueError("The checkpoint does not match the MangoCare detection classes.")
                if torch.cuda.is_available():
                    try:
                        model.predict(image, imgsz=size, conf=threshold, device=0, half=False, verbose=False)
                    except (RuntimeError, ValueError, AssertionError):
                        model.predictor = None
                        model.to("cpu")
                    else:
                        self.device = 0
                self.model = model
            try:
                result = self.model.predict(
                    image, imgsz=size, conf=threshold, device=self.device, half=False, verbose=False,
                )[0]
            except (RuntimeError, ValueError, AssertionError):
                if self.device == "cpu":
                    raise
                self.device = "cpu"
                self.model.predictor = None
                self.model.to("cpu")
                result = self.model.predict(image, imgsz=size, conf=threshold, device="cpu", verbose=False)[0]

        boxes = [
            {"label": CLASSES[int(cls)], "confidence": float(score), "box": box}
            for cls, score, box in zip(
                result.boxes.cls.tolist(), result.boxes.conf.tolist(), result.boxes.xyxyn.tolist(),
            )
        ]
        return sorted(boxes, key=lambda item: item["confidence"], reverse=True)


class Classifier:
    def __init__(self):
        self.weights = ROOT / os.environ.get("MANGOCARE_CLASSIFIER_WEIGHTS", "mangocare_classifier.pt")
        self.model = None
        self.lock = Lock()

    def predict(self, image):
        if not self.weights.is_file():
            raise FileNotFoundError(f"MangoCare classifier checkpoint is missing: {self.weights}")
        with self.lock:
            if self.model is None:
                from ultralytics import YOLO

                model = YOLO(str(self.weights))
                if model.task != "classify" or model.names != CLASSIFIER_LABELS:
                    raise ValueError("The checkpoint does not match the MangoCare classification classes.")
                self.model = model
            probabilities = self.model.predict(image, imgsz=224, device="cpu", verbose=False)[0].probs
            scores = probabilities.data.tolist()
            return {
                "label": CLASSIFIER_LABELS[probabilities.top1],
                "scores": {label: scores[index] for index, label in CLASSIFIER_LABELS.items()},
            }
