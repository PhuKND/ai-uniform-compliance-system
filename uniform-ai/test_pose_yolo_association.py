from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent
APP_DIR = ROOT_DIR / "app"
if str(APP_DIR) not in sys.path:
    sys.path.insert(0, str(APP_DIR))

from config import get_config
from pose_estimation import PoseEstimator
from pose_estimation.visualization import save_pose_validation_visualization
from services.yolov8_service import YoloV8Service
from uniform_validation import required_items_from_detections, validate_yolo_detections_for_pose


def main() -> int:
    parser = argparse.ArgumentParser(description="Test selected-pose YOLO uniform association.")
    parser.add_argument("--image", default="test.jpg", help="Image path relative to project root or absolute path.")
    parser.add_argument("--confidence", default=None, help="YOLO confidence threshold, for example 0.10.")
    parser.add_argument("--save-annotated", action="store_true", help="Save selected-pose visualization.")
    args = parser.parse_args()

    image_path = Path(args.image)
    if not image_path.is_absolute():
        image_path = ROOT_DIR / image_path
    if not image_path.exists():
        raise FileNotFoundError(f"Image not found: {image_path}")

    config = get_config()
    pose_result = PoseEstimator(config).estimate(image_path)
    selected_person = pose_result.get("selected_person")

    yolo_result = YoloV8Service(config).predict(
        image_path,
        confidence=args.confidence,
        save_annotated=False,
    )
    validation = validate_yolo_detections_for_pose(
        yolo_result.get("detections", []),
        selected_person,
        tuple(pose_result["image_shape"]),
        config,
    )
    required_items = required_items_from_detections(
        validation["accepted_detections"],
        config.REQUIRED_ITEM_PRESENT_THRESHOLD,
    )

    print(f"image={image_path}")
    print(f"person_count={pose_result['person_count']} selected={pose_result['selected']}")
    if selected_person:
        print(f"selected_bbox={selected_person['pose_bbox']} valid_keypoints={selected_person['valid_keypoint_count']}")
    print(f"raw_yolo={len(validation['raw_yolo_detections'])}")
    print(f"accepted={len(validation['accepted_detections'])}")
    print(f"rejected={len(validation['rejected_detections'])}")
    print(f"compliance_result={validation['compliance_result']}")
    print(f"required_items={required_items}")

    for detection in validation["accepted_detections"]:
        print(
            "ACCEPT "
            f"{detection['class_name']} conf={detection['confidence']} "
            f"overlap={detection['pose_overlap_ratio']} reason={detection['validation_reason']}"
        )
    for detection in validation["rejected_detections"]:
        print(
            "REJECT "
            f"{detection['class_name']} conf={detection['confidence']} "
            f"overlap={detection['pose_overlap_ratio']} reason={detection['validation_reason']}"
        )

    if args.save_annotated:
        annotated_path = save_pose_validation_visualization(
            image_path=image_path,
            selected_person=selected_person,
            accepted_detections=validation["accepted_detections"],
            rejected_detections=validation["rejected_detections"],
            output_dir=config.YOLO_OUTPUT_DIR,
            min_keypoint_confidence=config.POSE_MIN_CONFIDENCE,
            show_rejected=config.SHOW_REJECTED_UNIFORM_DETECTIONS,
        )
        print(f"annotated_image={annotated_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
