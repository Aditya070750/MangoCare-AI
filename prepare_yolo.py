
from pathlib import Path
import shutil

SOURCE = Path(r"D:\Mango_Disease\Mango Leaf Disease Detection.v1i.yolov8")
DEST = Path(r"D:\Mango_Disease\YOLO_3Class")

# Original class ID -> New class ID
CLASS_MAP = {
    0: 0,  # Anthracnose
    6: 1,  # Powdery Mildew
    5: 2,  # Healthy
}

if not SOURCE.is_dir():
    raise FileNotFoundError(f"Source dataset not found: {SOURCE}")

if DEST.exists() and any(DEST.iterdir()):
    raise RuntimeError(
        f"Destination is not empty: {DEST}. "
        "Use an empty folder to prevent stale files."
    )

total_images = 0
total_boxes = 0
class_counts = [0, 0, 0]

for split in ["train", "valid", "test"]:
    src_images = SOURCE / split / "images"
    src_labels = SOURCE / split / "labels"

    if not src_images.is_dir() or not src_labels.is_dir():
        raise FileNotFoundError(f"Missing images or labels in {split}")

    dst_images = DEST / split / "images"
    dst_labels = DEST / split / "labels"

    dst_images.mkdir(parents=True, exist_ok=True)
    dst_labels.mkdir(parents=True, exist_ok=True)

    image_count = 0
    box_count = 0
    skipped = 0

    for image in src_images.iterdir():
        if image.suffix.lower() not in (".jpg", ".jpeg", ".png"):
            continue

        label = src_labels / (image.stem + ".txt")

        if not label.exists():
            skipped += 1
            continue

        new_lines = []
        unwanted = False

        for line in label.read_text(encoding="utf-8").splitlines():
            parts = line.split()

            if len(parts) != 5:
                raise ValueError(f"Invalid YOLO annotation: {label}")

            old_id = int(parts[0])

            if old_id not in CLASS_MAP:
                unwanted = True
                break

            coords = [float(v) for v in parts[1:]]
            if not all(0 <= v <= 1 for v in coords):
                raise ValueError(f"Invalid box coordinates: {label}")

            new_id = CLASS_MAP[old_id]
            new_lines.append(
                " ".join([str(new_id)] + parts[1:])
            )

        if unwanted or not new_lines:
            skipped += 1
            continue

        shutil.copy2(image, dst_images / image.name)
        (dst_labels / label.name).write_text(
            "\n".join(new_lines) + "\n",
            encoding="utf-8"
        )

        for line in new_lines:
            class_counts[int(line.split()[0])] += 1

        image_count += 1
        box_count += len(new_lines)

    total_images += image_count
    total_boxes += box_count

    print(
        f"{split}: {image_count} images, "
        f"{box_count} boxes, {skipped} skipped"
    )

yaml_content = """path: D:/Mango_Disease/YOLO_3Class
train: train/images
val: valid/images
test: test/images

nc: 3
names:
  0: Anthracnose
  1: Powdery Mildew
  2: Healthy
"""

(DEST / "data.yaml").write_text(
    yaml_content, encoding="utf-8"
)

print("\nDataset preparation complete!")
print("Total images:", total_images)
print("Total bounding boxes:", total_boxes)

for name, count in zip(
    ["Anthracnose", "Powdery Mildew", "Healthy"],
    class_counts
):
    print(f"{name}: {count} boxes")

print("Saved to:", DEST)
