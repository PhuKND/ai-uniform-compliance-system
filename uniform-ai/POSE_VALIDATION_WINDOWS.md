# Pose-Based Target Student Validation

This project now uses human pose estimation to make face recognition and uniform scoring refer to one selected student only.

## Pose Model

- Implementation: Ultralytics YOLO pose (`yolov8n-pose.pt` by default).
- Reason: it is lightweight, Windows-friendly, and already fits the existing YOLOv8 stack.
- Output schema: COCO-17 body keypoints plus a person bounding box.
- The model can detect multiple people, but the system selects exactly one target person.

## Closest-Person Selection

The selected target student is the detected person with the largest pose/body bounding-box area, with pose confidence and visible keypoint count used as tie-breakers.

Returned API fields:

```json
{
  "pose": {
    "method": "largest_pose_area",
    "person_count": 2,
    "selected": true,
    "selected_person": {
      "pose_bbox": [2.38, 5.94, 237.0, 513.0],
      "pose_confidence": 0.91,
      "valid_keypoint_count": 14
    }
  }
}
```

Only `selected_person` is drawn, recognized, and scored.

## Face Recognition Link

Face detection may still find multiple faces, but recognition is run only on the face that matches the selected pose head region.

Matching uses:

- face center inside or near the selected pose head/body region
- overlap between the face box and selected pose head region
- distance from the face center to head keypoints such as nose/eyes/ears

If no face matches the selected pose:

```json
{
  "face_matched_to_selected_pose": false,
  "identity": null,
  "reason": "No detected face matches the selected closest person pose"
}
```

For direct face APIs, selected-pose linking is enabled by default:

```text
POST /api/face/identify
POST /api/face/verify
```

For legacy debugging only, pass `use_selected_pose=false`.

## YOLO Uniform Validation

YOLOv8 still uses exactly the official four classes:

```text
0: ao_so_mi_trang
1: ao_doan_thanh_nien
2: quan_tay_dai_den
3: khan_quang_do
```

After raw YOLO detection, every box is checked against the selected pose:

- `ao_so_mi_trang`, `ao_doan_thanh_nien`: must match selected upper body / torso.
- `quan_tay_dai_den`: must match selected lower body / legs.
- `khan_quang_do`: must match selected neck / upper chest.

The response separates:

```json
{
  "pose_validated_yolov8": {
    "raw_yolo_detections": [],
    "accepted_detections": [],
    "rejected_detections": [],
    "compliance_result": {
      "ao_so_mi_trang": false,
      "ao_doan_thanh_nien": false,
      "quan_tay_dai_den": false,
      "khan_quang_do": false
    },
    "summary": {
      "raw_count": 0,
      "accepted_count": 0,
      "rejected_count": 0
    }
  }
}
```

Uniform component scoring uses only `accepted_detections`.

## Visual Output

For every pose-validated prediction/evaluation, the backend saves and returns one final combined preview image:

- only the selected student skeleton is drawn
- accepted YOLO boxes are drawn normally
- rejected YOLO boxes are drawn as rejected boxes when `UNIFORM_SHOW_REJECTED_DETECTIONS=true`
- the image is generated after pose-YOLO association, so accepted/rejected boxes match the final scoring logic
- output image URL is returned in `processed_image_url`, `final_annotated_image_url`, and `pose_validated_yolov8.final_annotated_image_url`

The frontend should display this one combined image directly. Do not render separate YOLO and pose images.

## API Commands

Run server:

```powershell
.\.venv\Scripts\Activate.ps1
python app.py
```

Pose-validated YOLO test. Pose validation is enabled by default; `validate_pose=true` is shown for clarity:

```powershell
curl.exe -X POST http://127.0.0.1:5001/api/uniform/yolov8/predict ^
  -F "image=@uploads/20260504_003923_510b7e4ca95046bfbcb0011e99c469f7.png" ^
  -F "validate_pose=true" ^
  -F "confidence=0.10" ^
  -F "save_annotated=true"
```

Full AI student evaluation:

```powershell
curl.exe -X POST http://127.0.0.1:5001/api/ai/evaluate-student ^
  -F "image=@test.jpg" ^
  -F "face_mode=identify" ^
  -F "run_face=true" ^
  -F "run_uniform=true" ^
  -F "save_annotated=true"
```

Reusable local test script:

```powershell
.\.venv\Scripts\python.exe test_pose_yolo_association.py --image test.jpg --confidence 0.10 --save-annotated
```

## Configuration

Tune thresholds in `.env`:

```text
UNIFORM_POSE_MODEL=yolov8n-pose.pt
UNIFORM_POSE_IMGSZ=640
UNIFORM_POSE_PERSON_CONF=0.25
UNIFORM_POSE_MIN_KEYPOINT_CONF=0.40
UNIFORM_POSE_MIN_VALID_KEYPOINTS=5
UNIFORM_TARGET_PERSON_PADDING_RATIO=0.15
UNIFORM_MIN_COMPONENT_POSE_OVERLAP_RATIO=0.20
UNIFORM_MIN_SCARF_POSE_OVERLAP_RATIO=0.08
UNIFORM_MIN_COMPONENT_BODY_OVERLAP_RATIO=0.05
UNIFORM_MAX_COMPONENT_CENTER_DISTANCE_RATIO=0.35
UNIFORM_MIN_FACE_POSE_OVERLAP_RATIO=0.03
UNIFORM_MAX_FACE_HEAD_DISTANCE_RATIO=0.28
UNIFORM_MIN_FACE_POSE_MATCH_SCORE=0.18
UNIFORM_SHOW_REJECTED_DETECTIONS=true
```

## React Dashboard Fields

The React source is not present in this workspace. The dashboard can use these API fields:

- `pose.selected_person`: show "Học sinh được chọn" / "Dáng người được chọn"
- `face.face_matched_to_selected_pose`: show "Khuôn mặt khớp với dáng người"
- `pose_validated_yolov8.accepted_detections`: show "Thành phần đồng phục hợp lệ"
- `pose_validated_yolov8.rejected_detections`: show "Thành phần bị loại" and "Lý do loại"
- `pose_validated_yolov8.compliance_result`: show "Kết quả chấm điểm đồng phục"
- `processed_image_url` / `final_annotated_image_url`: display the one combined final preview image
- `pose_validated_yolov8.final_annotated_image_url`: nested copy of the same combined image URL

## Why This Prevents the False Positive

If the selected student stands on the right but YOLO detects a shirt/trousers box on the left, that box has little or no overlap with the selected pose region. Its center is also far from the selected torso/leg keypoints, so it is placed in `rejected_detections` and never contributes to `required_items` or the final compliance result.
