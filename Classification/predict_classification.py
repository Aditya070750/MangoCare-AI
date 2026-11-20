"""Classify one mango-leaf photo using a trained binary checkpoint."""

import argparse
import json
from pathlib import Path


LABELS = {0: "Anthracnose", 1: "Healthy"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("photo", type=Path)
    parser.add_argument("--weights", type=Path, required=True)
    args = parser.parse_args()
    if not args.photo.is_file():
        parser.error(f"Photo not found: {args.photo}")
    if args.weights.suffix.lower() != ".pt" or not args.weights.is_file():
        parser.error("Pass the local best.pt from the classification run.")

    from PIL import Image, ImageOps
    from ultralytics import YOLO

    with Image.open(args.photo) as source:
        photo = ImageOps.exif_transpose(source).convert("RGB")
    model = YOLO(str(args.weights))
    if model.task != "classify" or model.names != LABELS:
        parser.error("This checkpoint does not match the MangoCare binary classifier.")
    result = model.predict(photo, imgsz=224, device="cpu", verbose=False)[0]
    scores = result.probs.data.tolist()
    print(json.dumps({
        "prediction": LABELS[result.probs.top1],
        "scores": {label: scores[class_id] for class_id, label in LABELS.items()},
    }, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ImportError, RuntimeError) as exc:
        raise SystemExit(f"Classification failed: {exc}") from exc
