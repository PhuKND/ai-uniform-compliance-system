from __future__ import annotations

import argparse
from pathlib import Path

from ultralytics import YOLO

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
VIDEO_EXTENSIONS = {".mp4", ".avi", ".mov", ".mkv", ".wmv", ".m4v"}
CLASS_NAMES = ['ao_so_mi_trang', 'ao_doan_thanh_nien', 'quan_tay_dai_den', 'khan_quang_do', 'quan_short_tay_den', 'quan_dai_trang']


def parse_source(value: str):
    value = value.strip()
    if value.isdigit():
        return int(value)

    source_path = Path(value).expanduser()
    if not source_path.exists():
        raise FileNotFoundError(f"Source path not found: {source_path}")

    allowed_extensions = IMAGE_EXTENSIONS | VIDEO_EXTENSIONS
    if source_path.is_file() and source_path.suffix.lower() not in allowed_extensions:
        raise ValueError(f"Unsupported source extension: {source_path.suffix}")

    return str(source_path)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run school-uniform YOLOv8 inference on Windows 11 64-bit."
    )
    parser.add_argument(
        "--source",
        required=True,
        help="Image, folder, video, or webcam index such as 0.",
    )
    parser.add_argument(
        "--weights",
        default="best.pt",
        help="Weights path. Default: best.pt next to this script.",
    )
    parser.add_argument("--conf", type=float, default=0.25)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument(
        "--output",
        default="runs_uniform_predict",
        help="Folder for annotated outputs.",
    )
    args = parser.parse_args()

    package_dir = Path(__file__).resolve().parent
    weights_path = Path(args.weights).expanduser()
    if not weights_path.is_absolute():
        weights_path = package_dir / weights_path
    if not weights_path.is_file():
        raise FileNotFoundError(f"Weights not found: {weights_path}")

    model = YOLO(str(weights_path))
    results = model.predict(
        source=parse_source(args.source),
        conf=args.conf,
        imgsz=args.imgsz,
        save=True,
        project=str(Path(args.output).expanduser()),
        name="predict",
        exist_ok=True,
        verbose=False,
    )

    for result in results:
        source_name = Path(getattr(result, "path", str(args.source))).name
        box_count = 0 if result.boxes is None else len(result.boxes)
        print(f"{source_name}: {box_count} detections")
        if result.boxes is None:
            continue

        for box in result.boxes:
            class_id = int(box.cls.detach().cpu().item())
            confidence = float(box.conf.detach().cpu().item())
            class_name = result.names.get(
                class_id,
                CLASS_NAMES[class_id] if 0 <= class_id < len(CLASS_NAMES) else str(class_id),
            )
            xyxy = [round(float(value), 2) for value in box.xyxy[0].detach().cpu().tolist()]
            print(f"  {class_name}: conf={confidence:.3f}, box_xyxy={xyxy}")

    if results:
        print(f"Annotated outputs saved to: {results[0].save_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
