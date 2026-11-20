"""Train a YOLO11n detector for three mango leaf classes."""

import gc
import logging
from pathlib import Path
import shutil
import sys
from tempfile import TemporaryDirectory


DATA_YAML = Path(r"D:\Mango_Disease\YOLO_3Class\data.yaml")
RESULTS_DIR = Path(__file__).resolve().parent / "models" / "yolo_detection"
WEIGHTS = "yolo11n.pt"
CLASS_NAMES = {0: "Anthracnose", 1: "Powdery Mildew", 2: "Healthy"}
SEED = 42
LOGGER = logging.getLogger(__name__)


def stage_dataset(config, staging_dir):
    """Copy the dataset so Ultralytics can safely cache labels or repair images."""
    source = DATA_YAML.parent.resolve()
    for split in ("train", "val", "test"):
        images = (source / config[split]).resolve()
        config[split] = images.relative_to(source).as_posix()
        if not images.is_dir() or not (images.parent / "labels").is_dir():
            raise FileNotFoundError(f"Missing images or labels for {split}: {images}")

    LOGGER.info("Copying the dataset to a temporary training directory.")
    shutil.copytree(source, staging_dir, ignore=shutil.ignore_patterns("*.cache"))
    config["path"] = staging_dir.as_posix()
    return staging_dir / DATA_YAML.name


def main():
    import torch
    import yaml
    from ultralytics import YOLO

    LOGGER.info("Reading dataset configuration from %s", DATA_YAML)
    config = yaml.safe_load(DATA_YAML.read_text(encoding="utf-8"))
    if config.get("nc") != len(CLASS_NAMES) or config.get("names") != CLASS_NAMES:
        raise ValueError(f"Unexpected class mapping in {DATA_YAML}. Expected {CLASS_NAMES}.")
    LOGGER.info("Classes: %s", ", ".join(CLASS_NAMES.values()))

    device = "cpu"
    if torch.cuda.is_available():
        try:
            # A kernel call also checks that this PyTorch build supports the GPU.
            torch.zeros(1, device="cuda:0").sum().item()
            LOGGER.info("Using CUDA device 0 (%s).", torch.cuda.get_device_name(0))
            device = 0
        except RuntimeError as exc:
            LOGGER.warning("CUDA device check failed: %s", exc)
    if device == "cpu":
        LOGGER.info("Using CPU because CUDA is unavailable or unsupported.")

    LOGGER.info("Training results will be saved under %s", RESULTS_DIR)
    with TemporaryDirectory(prefix="mango-yolo-") as temp_dir:
        staged_yaml = stage_dataset(config, Path(temp_dir) / "dataset")
        staged_yaml.write_text(yaml.safe_dump(config, sort_keys=False), encoding="utf-8")
        for batch in (8, 4, 2, 1):
            LOGGER.info("Starting %s with image size 640, batch %d, 50 epochs, seed %d.",
                        WEIGHTS, batch, SEED)
            model = YOLO(WEIGHTS, task="detect")
            try:
                model.train(
                    data=str(staged_yaml),
                    device=device,
                    imgsz=640,
                    batch=batch,
                    epochs=50,
                    seed=SEED,
                    deterministic=True,
                    cache=False,
                    workers=0,
                    project=str(RESULTS_DIR),
                    name="yolo11n",
                    exist_ok=False,
                )
            except (MemoryError, RuntimeError) as exc:
                message = str(exc).lower()
                memory_error = isinstance(exc, (MemoryError, torch.cuda.OutOfMemoryError))
                if not memory_error and not any(marker in message for marker in (
                    "out of memory", "can't allocate memory", "cannot allocate memory",
                    "not enough memory", "cublas_status_alloc_failed",
                )):
                    raise
                if batch == 1:
                    raise RuntimeError("Training ran out of memory even with batch size 1.") from exc
                LOGGER.warning("Insufficient memory at batch %d. Restarting from %s with batch %d.",
                               batch, WEIGHTS, batch // 2)
            else:
                LOGGER.info("Training complete. Results: %s", model.trainer.save_dir)
                LOGGER.info("Best checkpoint: %s", model.trainer.best)
                return

            del model
            gc.collect()
            if device != "cpu":
                torch.cuda.empty_cache()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
    try:
        main()
    except KeyboardInterrupt:
        LOGGER.warning("Training interrupted.")
        sys.exit(130)
    except ImportError:
        LOGGER.exception("Could not load training dependencies. Install Ultralytics and PyTorch.")
        sys.exit(1)
    except Exception:
        LOGGER.exception("Mango leaf detection training failed.")
        sys.exit(1)
