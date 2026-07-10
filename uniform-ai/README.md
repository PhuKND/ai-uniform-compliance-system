# Uniform AI Integrated Server

This project runs one Flask AI server for the graduation project: evaluating middle-school uniform rule compliance and student identity.

The single server includes:

- Face recognition with InsightFace.
- Human pose estimation with YOLO pose to select one closest target student.
- Uniform evaluation with Grounding DINO V2, SCHP ATR, Florence-2, and RuleEngine.
- YOLOv8 V2 detection using the official 6-class uniform model from `yolov8_6class/best.pt`, filtered by the selected target pose.
- Combined identity + uniform evaluation for backend, web, and mobile integration.

Run from the project root:

```powershell
python app.py
```

Default server:

```text
http://127.0.0.1:5001
```

Main endpoints:

```text
GET  /api/ai/health
POST /api/ai/evaluate-student
POST /api/realtime-camera/analyze-frame
POST /api/face/enroll
POST /api/face/verify
POST /api/face/identify
POST /api/uniform/evaluate
POST /api/uniform/yolov8/predict
POST /api/uniform/admin/select-evaluation
```

`POST /api/uniform/evaluate` keeps the legacy comparison flow and returns two admin-review candidates:

- `GROUNDING_DINO_V2`
- `YOLOV8_V2`

Advanced single-method endpoints are also available:

- `POST /api/uniform/evaluate/yolov8-v2`
- `POST /api/uniform/evaluate/grounding-dino-v2`

Each candidate saves one processed image and one JSON sidecar under `outputs/yolov8`. The response includes
`pre_ai_image`, `pre_ai_image_url`, `candidates`, and `evaluation_id`. Use
`POST /api/uniform/admin/select-evaluation` with `evaluation_id` and `selected_method` to persist the admin choice
to local JSON-backed storage under `storage/uniform/selections`.

Before inference, uploaded uniform images are stored under `storage/uniform/pre_ai`. Images under 1MB are kept
unchanged; larger images are compressed below 1MB while preserving aspect ratio.

Pose validation is enabled by default. The processed image contains the selected pose skeleton and pose-validated
component boxes. All visible output-image labels are Vietnamese:

- selected student: `học sinh được chọn`
- accepted components: `áo sơ mi trắng`, `áo đoàn thanh niên`, `quần tây dài đen`, `khăn quàng đỏ`, `quần short đen`, `quần dài trắng`
- rejected outside-body detections: `bị từ chối: vì nằm ngoài cơ thể học sinh`

Official active uniform classes:

```text
0: ao_so_mi_trang
1: ao_doan_thanh_nien
2: quan_tay_dai_den
3: khan_quang_do
4: quan_short_tay_den
5: quan_dai_trang
```

Full Windows setup, API request/response examples, Java Spring Boot, ReactJS, Android integration, and troubleshooting are in [HDSD.txt](./HDSD.txt).

Pose-based target-student selection and YOLO filtering are documented in [POSE_VALIDATION_WINDOWS.md](./POSE_VALIDATION_WINDOWS.md).

## Real-Time Camera Endpoint

The admin camera page in `UNIFORM-LIB-DEPLOY` calls the backend endpoint
`POST /api/admin/realtime-camera/analyze-frame`, and the backend proxies each frame to this Flask endpoint:

```text
POST /api/realtime-camera/analyze-frame
```

Run the AI service:

```powershell
cd C:\Users\KaNija\Desktop\DATN\Code3\UNIFORM-LIB\uniform-ai
.\.venv\Scripts\Activate.ps1
python app.py
```

Required local assets:

- `yolov8_6class/best.pt` for uniform detections. Production does not fallback to `last.pt`.
- `yolov8n-pose.pt` in the project root, or another local YOLOv8 pose model path supplied by request/env.
- InsightFace enrollment data in `storage/students.json` and `storage/embeddings/`.

Accepted multipart fields include `image`, `frame_width`, `frame_height`, `confidence_threshold`,
`face_threshold`, `frame_size`, `yolov8_pose_model`, `yolov8_uniform_model`,
`insightface_config`, and `face_embedding_source`.

This real-time endpoint uses only YOLOv8 Pose, InsightFace, and YOLOv8 Uniform. It does not use Grounding DINO,
SCHP, Florence-2, RuleEngine, or the normal slow uniform evaluation pipeline.

PowerShell smoke test:

```powershell
curl.exe -X POST "http://127.0.0.1:5001/api/realtime-camera/analyze-frame" `
  -F "image=@test.jpg" `
  -F "frame_width=540" `
  -F "frame_height=960" `
  -F "run_pose=true" `
  -F "run_face=true" `
  -F "run_uniform=true" `
  -F "save_annotated=false" `
  -F "use_grounding_dino=false" `
  -F "use_schp=false" `
  -F "use_florence=false"
```

The response contains `success`, `message`, `frame_width`, `frame_height`, `processing_time_ms`,
`selected_person`, `identity`, `face`, `uniform_detections`, and `pipeline`.
