# Uniform Management Backend

Spring Boot backend for the graduation project: evaluating middle-school uniform rule compliance.

## Database

Default database: MySQL on port `3307`.

Create the database once in MySQL Workbench:

```sql
CREATE DATABASE uniform_management CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Default connection properties are in `src/main/resources/application.properties`.

Recommended Windows PowerShell environment variables:

```powershell
$env:DB_URL="jdbc:mysql://localhost:3307/uniform_management?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh&characterEncoding=UTF-8"
$env:DB_USERNAME="root"
$env:DB_PASSWORD="your_mysql_password"
$env:JWT_SECRET="replace-with-at-least-32-characters-secret"
$env:UNIFORM_ADMIN_EMAIL="admin@uniform.local"
$env:UNIFORM_ADMIN_PASSWORD="Admin@123456"
$env:UNIFORM_AI_BASE_URL="http://127.0.0.1:5001"
$env:UNIFORM_AI_FACE_BASE_URL="http://127.0.0.1:5001"
$env:UNIFORM_AI_OUTPUT_ROOT=(Resolve-Path "..\..\uniform-lib\uniform-ai\outputs").Path
$env:UNIFORM_AI_REALTIME_ENDPOINT="/api/realtime-camera/analyze-frame"
$env:UNIFORM_AI_REALTIME_YOLOV8_POSE_MODEL="models/yolov8-pose.pt"
$env:UNIFORM_AI_REALTIME_YOLOV8_UNIFORM_MODEL="models/yolov8-uniform.pt"
$env:UNIFORM_AI_REALTIME_INSIGHTFACE_CONFIG="buffalo_l"
$env:UNIFORM_AI_REALTIME_FACE_EMBEDDING_SOURCE="registered-face-data"
$env:UNIFORM_SCHEDULE_TIME_ZONE="Asia/Ho_Chi_Minh"
```

The application uses `spring.jpa.hibernate.ddl-auto=update` by default so Hibernate creates/updates tables after the database exists.

## Run Uniform AI

From `../uniform-ai`:

```powershell
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
python app.py
```

Default AI server:

```text
http://127.0.0.1:5001
```

The Java backend calls these existing AI endpoints:

- `POST /api/ai/evaluate-student`
- `POST /api/face/enroll`
- `POST /api/face/enroll-sample`
- `DELETE /api/face/students/{studentId}`
- `PATCH /api/face/students/{studentId}/rename`
- `GET /api/uniform/yolov8/outputs/{filename}`
- `POST /api/realtime-camera/analyze-frame` for the fast real-time camera pipeline

## Run Backend

From this `backend` folder:

```powershell
mvn clean test
mvn spring-boot:run
```

Backend URL:

```text
http://localhost:8080
```

Default seeded admin is created only if no admin exists:

```text
email: admin@uniform.local
password: Admin@123456
```

Change it with `UNIFORM_ADMIN_EMAIL` and `UNIFORM_ADMIN_PASSWORD`.

## Student ID Rule

The backend generates `studentCode` and `faceDataId` from the last word of the full name:

```text
LASTNAME + NUMBER(001-999)
```

Examples:

- `Nguyễn Văn An` -> `AN001`
- `Lê Minh An` -> `AN002`
- `Trần Phước Phú` -> `PHU001` if no `PHU` exists

Vietnamese accents are normalized, text is uppercased, and special characters are removed. The database uses an internal numeric primary key, so changing a student's name can safely regenerate `studentCode` and `faceDataId` without breaking history foreign keys. The Java backend also calls the new Flask face rename endpoint to keep stored embeddings aligned when face data exists.

## Morality Score

Each student starts with `100` points. Levels:

- `score > 80`: `Tốt`
- `65 < score <= 80`: `Khá`
- `50 < score <= 65`: `Trung bình`
- `score <= 50`: `Yếu`

Official uniform violations deduct morality points and create a `MoralityScoreLog`.

## Evaluation Workflow

1. Admin uploads an image to `POST /api/evaluations/compare` or `/api/evaluations/run`.
2. Backend calls Uniform AI twice:
   - Method 1: `uniform_method=method_1` = GroundingDINO + SCHP + Florence-2.
   - Method 2: `uniform_method=method_2` = YOLOv8 + SCHP + Florence-2.
3. Uniform AI selects the closest pose target, matches the selected face, evaluates only that target, and returns processed image URLs.
4. Backend validates each returned output path against `UNIFORM_AI_OUTPUT_ROOT`, verifies the image, and stores it
   in MySQL as a `LONGBLOB` row in `evaluation_images`. If the two services do not share a filesystem, a secured
   same-origin HTTP bridge downloads the same AI image instead.
5. Frontend displays images with:

```text
GET /api/images/{id}
```

6. Admin chooses the official result:

```text
POST /api/evaluations/{runId}/choose-official
```

Only the chosen method becomes official `evaluation_history`. The non-selected raw method remains attached to the comparison run/history for audit.
The `deductedPoints` field is the final non-negative integer deduction selected by the admin (maximum `100`);
when omitted by an older client, the backend keeps the automatic suggestion for backward compatibility.

## Weekly Uniform Schedule

Admins configure per-class weekday requirements from actual `Student.className` values. The backend stores one schedule row per class and weekday; an explicitly empty day is saved and is different from a missing schedule.

- Class selector source: `GET /api/students/classes`
- Read weekly schedule: `GET /api/admin/uniform-requirement-schedules/{classId}`
- Replace weekly schedule: `PUT /api/admin/uniform-requirement-schedules/{classId}`

`classId` is the URL-encoded class name. Schedule evaluation uses `UNIFORM_SCHEDULE_TIME_ZONE`, defaulting to `Asia/Ho_Chi_Minh`, resolves the weekday at evaluation time, scores only final unique detections, and saves the schedule snapshot into both comparison run JSON and official history.

### Repairing historical processed images

The internal startup backfill is disabled by default. It is idempotent and never exposes a maintenance endpoint.
To repair one run whose source files still exist under the approved output root, start the backend once with:

```powershell
$env:UNIFORM_AI_OUTPUT_ROOT=(Resolve-Path "..\..\uniform-lib\uniform-ai\outputs").Path
$env:UNIFORM_AI_IMAGE_BACKFILL_ENABLED="true"
$env:UNIFORM_AI_IMAGE_BACKFILL_RUN_ID="35"
mvn spring-boot:run
```

Stop the process after the `ai_processed_image_backfill_complete` log line, then unset the two backfill variables.
Use run ID `0` to scan all completed runs that have a stored processed-image path but no managed image reference.

## Main APIs

Authentication:

- `POST /api/auth/register`
- `POST /api/auth/login`

Students:

- `GET /api/students`
- `GET /api/students/search`
- `GET /api/students/classes`
- `POST /api/students`
- `GET /api/students/{studentCode}`
- `PUT /api/students/{studentCode}`
- `DELETE /api/students/{studentCode}`
- `GET /api/students/me`
- `PATCH /api/students/me`
- `DELETE /api/students/me`
- `POST /api/students/me/password`

Face data:

- `GET /api/face-data`
- `GET /api/face-data/{studentCode}`
- `POST /api/face-data/{studentCode}`
- `PUT /api/face-data/{studentCode}`
- `DELETE /api/face-data/{studentCode}`

Evaluations:

- `POST /api/evaluations/run`
- `POST /api/evaluations/compare`
- `POST /api/evaluations/{runId}/choose-official`

Uniform schedules:

- `GET /api/admin/uniform-requirement-schedules/{classId}`
- `PUT /api/admin/uniform-requirement-schedules/{classId}`

Real-time camera:

- `POST /api/admin/realtime-camera/analyze-frame`

The real-time endpoint is admin-only and proxies to the fast AI endpoint configured by
`UNIFORM_AI_REALTIME_ENDPOINT`. It is intended to use YOLOv8 Pose, InsightFace, and
YOLOv8 Uniform only. It does not call Grounding DINO, SCHP, Florence-2, or the backend
uniform compliance service.

History:

- `GET /api/evaluation-history`
- `GET /api/evaluation-history/search`
- `GET /api/evaluation-history/{id}`
- `PUT /api/evaluation-history/{id}`
- `DELETE /api/evaluation-history/{id}`
- `GET /api/evaluation-history/me`
- `GET /api/evaluation-history/me/{id}`

Correction requests:

- `POST /api/correction-requests`
- `GET /api/correction-requests`
- `GET /api/correction-requests/me`
- `POST /api/correction-requests/{id}/cancel`
- `POST /api/correction-requests/{id}/approve`
- `POST /api/correction-requests/{id}/reject`

Students create a request as `multipart/form-data` with `evaluationHistoryId`, `requestedDeduction`, `reason`,
and optional `evidenceNote`/`evidenceImage`. The backend derives the student from the authenticated account,
captures the deduction at submission, and allows at most one pending request for the same history. Approval sets
the official history deduction directly to `requestedDeduction`; rejection leaves it unchanged. Both decisions
record the reviewer, response, decision time, and deduction after the decision.

Statistics:

- `GET /api/statistics/admin`
- `GET /api/statistics/student/me`

Images:

- `GET /api/images/{id}`

## Permissions

Admins can manage students, face data, evaluations, history, correction approvals, and global statistics.

Students can register, log in, view/edit allowed personal fields, request profile deletion, change password, view only their own evaluation history/statistics, and submit/cancel correction requests.

Students cannot change full name, change student ID, edit face data, run uniform evaluation, edit history, or delete history.

## Example API Calls

Login:

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/auth/login" `
  -ContentType "application/json" `
  -Body '{"email":"admin@uniform.local","password":"Admin@123456"}'
```

Run comparison:

```powershell
curl.exe -X POST http://localhost:8080/api/evaluations/compare ^
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" ^
  -F "image=@..\uniform-ai\main-test-image-1.jpg" ^
  -F "studentCode=PHU002"
```

Choose official:

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/evaluations/1/choose-official" `
  -Headers @{ Authorization = "Bearer YOUR_ADMIN_TOKEN" } `
  -ContentType "application/json" `
  -Body '{"selectedMethod":"METHOD_2_YOLOV8_SCHP_FLORENCE","deductedPoints":5,"adminNote":"Chọn kết quả YOLOv8 rõ hơn"}'
```
