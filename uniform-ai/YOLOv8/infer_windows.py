from __future__ import annotations

import argparse
from pathlib import Path

from ultralytics import YOLO


def parse_source(value: str):
    return int(value) if value.isdigit() else value


def main() -> int:
    parser = argparse.ArgumentParser(description="Run school-uniform YOLOv8 inference on Windows 11.")
    parser.add_argument("--source", required=True, help="Image, video, folder, or webcam index such as 0.")
    parser.add_argument("--weights", default="best.pt", help="Path to model weights. Default: best.pt in this folder.")
    parser.add_argument("--conf", type=float, default=0.25, help="Confidence threshold.")
    parser.add_argument("--imgsz", type=int, default=640, help="Inference image size.")
    parser.add_argument("--output", default="runs_uniform_predict", help="Output folder for annotated predictions.")
    args = parser.parse_args()

    package_dir = Path(__file__).resolve().parent
    weights_path = Path(args.weights)
    if not weights_path.is_absolute():
        weights_path = package_dir / weights_path
    if not weights_path.exists():
        raise FileNotFoundError(f"Weights not found: {weights_path}")

    output_path = Path(args.output)
    if not output_path.is_absolute():
        output_path = package_dir / output_path

    model = YOLO(str(weights_path))
    results = model.predict(
        source=parse_source(args.source),
        conf=args.conf,
        imgsz=args.imgsz,
        save=True,
        project=str(output_path),
        name="predict",
        exist_ok=True,
    )

    for result in results:
        print(f"Source: {getattr(result, 'path', args.source)}")
        if result.boxes is None or len(result.boxes) == 0:
            print("  No detections")
            continue
        for box in result.boxes:
            class_id = int(box.cls.detach().cpu().item())
            confidence = float(box.conf.detach().cpu().item())
            class_name = result.names.get(class_id, str(class_id))
            xyxy = [round(float(value), 2) for value in box.xyxy[0].detach().cpu().tolist()]
            print(f"  {class_name}: conf={confidence:.3f}, box_xyxy={xyxy}")

    if results:
        print(f"Annotated outputs saved to: {results[0].save_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
