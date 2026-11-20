"""Build a reproducible binary-classification dataset and a Colab upload ZIP."""

from collections import defaultdict
from dataclasses import dataclass
from hashlib import sha256
import json
from pathlib import Path
import random
from tempfile import TemporaryDirectory
from zipfile import ZipFile, ZIP_DEFLATED

from PIL import Image, ImageOps


ROOT = Path(__file__).resolve().parent
CLASSES = ("Anthracnose", "Healthy")
SPLITS = {"train": .70, "val": .15, "test": .15}
SEED = 42
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}


@dataclass(frozen=True)
class Photo:
    path: Path
    digest: str
    phash: int
    source_name: str


def rgb(path):
    with Image.open(path) as source:
        return ImageOps.exif_transpose(source).convert("RGB")


def fingerprint(path):
    image = rgb(path)
    digest = sha256(f"{image.size}:".encode() + image.tobytes()).hexdigest()
    pixels = list(image.convert("L").resize((9, 8), Image.Resampling.LANCZOS).getdata())
    phash = 0
    for y in range(8):
        for x in range(8):
            i = y * 9 + x
            phash = (phash << 1) | (pixels[i] > pixels[i + 1])
    return Photo(path, digest, phash, path.stem.lower().split(".rf.")[0])


def group_similar(photos):
    # Connected components keep linked near-duplicates and Roboflow variants together.
    remaining = set(range(len(photos)))
    groups = []
    while remaining:
        first = min(remaining)
        remaining.remove(first)
        group = [first]
        for index in group:
            photo = photos[index]
            neighbors = {
                candidate for candidate in remaining
                if (photo.phash ^ photos[candidate].phash).bit_count() <= 4
                or photo.source_name == photos[candidate].source_name
            }
            remaining.difference_update(neighbors)
            group.extend(sorted(neighbors))
        groups.append([photos[index] for index in group])
    return groups


def split_groups(groups, rng):
    if len(groups) < len(SPLITS):
        raise ValueError("At least three independent photo groups are needed per class.")
    rng.shuffle(groups)
    groups.sort(key=len, reverse=True)
    total = sum(map(len, groups))
    assigned = {split: [] for split in SPLITS}
    for index, group in enumerate(groups):
        empty = [split for split, items in assigned.items() if not items]
        candidates = empty if len(groups) - index == len(empty) else SPLITS
        split = max(candidates, key=lambda name: SPLITS[name] * total - len(assigned[name]))
        assigned[split].extend(group)
    return assigned


def main():
    output = ROOT / "prepared"
    if output.exists():
        raise FileExistsError(f"{output} already exists. Move it aside before rebuilding.")
    counts = {}
    digests = {}
    partitions = {}
    rng = random.Random(SEED)
    for label in CLASSES:
        unique = {}
        paths = sorted(p for p in (ROOT / label).rglob("*") if p.suffix.lower() in IMAGE_SUFFIXES)
        for path in paths:
            photo = fingerprint(path)
            other = digests.setdefault(photo.digest, label)
            if other != label:
                raise ValueError(f"Identical image assigned to both classes: {path}")
            unique.setdefault(photo.digest, photo)
        groups = group_similar(list(unique.values()))
        partitions[label] = split_groups(groups, rng)
        counts[label] = {
            "source_files": len(paths), "unique_images": len(unique), "groups": len(groups),
            **{split: len(photos) for split, photos in partitions[label].items()},
        }

    with TemporaryDirectory(prefix="classification-", dir=ROOT) as temp:
        staging = Path(temp) / "prepared"
        dataset = staging / "dataset"
        manifest = {"seed": SEED, "counts": counts, "images": []}
        for label, split_photos in partitions.items():
            for split, photos in split_photos.items():
                folder = dataset / split / label
                folder.mkdir(parents=True)
                for photo in photos:
                    destination = folder / f"{photo.digest[:20]}.jpg"
                    rgb(photo.path).save(destination, "JPEG", quality=95)
                    manifest["images"].append({
                        "source": photo.path.relative_to(ROOT).as_posix(),
                        "output": destination.relative_to(dataset).as_posix(),
                    })
        (staging / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        with ZipFile(staging / "MangoCare_Classification_Dataset.zip", "w", ZIP_DEFLATED) as archive:
            for image in sorted(dataset.rglob("*.jpg")):
                archive.write(image, image.relative_to(dataset).as_posix())
        staging.rename(output)
    print(json.dumps(counts, indent=2))
    print(f"Upload this ZIP to Colab's Drive folder: {output / 'MangoCare_Classification_Dataset.zip'}")


if __name__ == "__main__":
    main()
