# Face Recognition Service

## How to run
cd "C:\Users\KaNija\Desktop\DATN\Code3\face-recognition-lib\face-recognition-service"
.\.venv\Scripts\Activate.ps1
python run.py

Flask API for student face enrollment, identification, and verification using InsightFace `FaceAnalysis`. The service detects faces, extracts embeddings, and matches them with cosine similarity.

The current implementation supports **multiple face samples per student**. A student can have front, left-angle, right-angle, or other enrolled samples, and matching uses the best similarity across that student's samples.

## Project Structure

```text
app/
  __init__.py                 Flask app factory, CORS, services, error handlers
  config.py                   Environment-based configuration
  routes/
    face_routes.py            API endpoints
  services/
    face_engine.py            Cached InsightFace loader and embedding extraction
    student_repository.py     Local multi-sample metadata and embedding storage
  utils/
    file_utils.py             Upload validation and safe file saving
    response_utils.py         Consistent success/error JSON responses
storage/
  students.json               Student metadata and sample records
  embeddings/
    <student_id>/
      <sample_id>.npy         New multi-sample embedding files
    <student_id>.npy          Legacy v1 embedding files, still supported
  uploads/
    enroll/                   First enrollment uploads
    enroll-sample/            Additional sample uploads
    verify/                   Verify request uploads
    identify/                 Identify request uploads
run.py                        Local Flask entrypoint
requirements.txt              Python dependencies
.env.example                  Environment variable template
```

## Installation

Use Python 3.10+.

```powershell
cd "C:\Users\KaNija\Desktop\DATN\Code3\face-recognition-lib\face-recognition-service"
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
Copy-Item .env.example .env
```

If PowerShell blocks virtualenv activation:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\.venv\Scripts\Activate.ps1
```

## InsightFace Runtime

CPU is the default mode:

```env
INSIGHTFACE_CTX_ID=-1
INSIGHTFACE_PROVIDERS=CPUExecutionProvider
```

The first request that uses face recognition may download the configured InsightFace model, usually `buffalo_l`, if it is not already cached.

To switch to GPU later:

```powershell
python -m pip uninstall -y onnxruntime
python -m pip install onnxruntime-gpu
```

Then update `.env`:

```env
INSIGHTFACE_CTX_ID=0
INSIGHTFACE_PROVIDERS=CUDAExecutionProvider,CPUExecutionProvider
```

CUDA, cuDNN, GPU driver, and `onnxruntime-gpu` versions must be compatible.

## Configuration

Copy `.env.example` to `.env`.

Important variables:

```env
FLASK_HOST=0.0.0.0
FLASK_PORT=5000
FLASK_DEBUG=false
CORS_ORIGINS=http://localhost:3000,http://localhost:5173,http://127.0.0.1:5173
SIMILARITY_THRESHOLD=0.5
MAX_CONTENT_LENGTH_MB=8
ALLOWED_IMAGE_EXTENSIONS=jpg,jpeg,png,webp
STORAGE_DIR=storage
UPLOAD_DIR=storage/uploads
EMBEDDING_DIR=storage/embeddings
METADATA_FILE=storage/students.json
INSIGHTFACE_MODEL_NAME=buffalo_l
INSIGHTFACE_CTX_ID=-1
INSIGHTFACE_PROVIDERS=CPUExecutionProvider
INSIGHTFACE_DET_SIZE=640,640
```

`SIMILARITY_THRESHOLD` controls whether a match is accepted. Higher values are stricter. Lower values are more permissive.

## Run the API

```powershell
cd "C:\Users\KaNija\Desktop\DATN\Code3\face-recognition-lib\face-recognition-service"
.\.venv\Scripts\Activate.ps1
python run.py
```

Base URL:

```text
http://localhost:5000
```

Stop the server with `Ctrl + C`.

## JSON Response Format

Success:

```json
{
  "success": true,
  "message": "Human readable message",
  "data": {}
}
```

Error:

```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human readable error"
  }
}
```

## Multi-Sample Enrollment Model

The API now uses Option A:

- `POST /api/face/enroll` creates a new student and stores the first face sample.
- `POST /api/face/enroll-sample` adds another face sample for an existing student.
- Calling `POST /api/face/enroll` again with an existing `student_id` still returns `STUDENT_ALREADY_EXISTS`.

Each sample stores:

- `sample_id`
- `embedding_file`
- `created_at`
- optional `sample_label`
- optional `source_image`
- optional face metadata such as `bbox` and `detection_score`

New samples are saved under:

```text
storage/embeddings/<student_id>/<sample_id>.npy
```

Legacy one-file-per-student data is still supported. If `storage/students.json` contains old v1 records with `embedding_file`, the repository migrates metadata into the v2 `samples` structure and keeps loading the old `.npy` files safely.

## Matching Logic

For verify:

1. Extract one query embedding from the uploaded image.
2. Compare it against every loaded sample for the requested student.
3. Use the maximum similarity across that student's samples.
4. Return `verified=true` when the best similarity is greater than or equal to `SIMILARITY_THRESHOLD`.

For identify:

1. Detect all faces in the uploaded image.
2. Extract an embedding for each detected face.
3. Compare each query embedding against all samples for all students.
4. Pick the sample with the maximum similarity.
5. Return the matched student only if that similarity passes the threshold.

This means multiple angles can improve matching because a query only needs to be close to one strong enrolled sample for that student.

## API Endpoints

### Health Check

`GET /api/face/health`

```powershell
curl.exe http://localhost:5000/api/face/health
```

Sample response:

```json
{
  "success": true,
  "message": "Face service is healthy",
  "data": {
    "status": "ok",
    "service": "face-recognition-service",
    "model_name": "buffalo_l",
    "model_loaded": false,
    "providers": ["CPUExecutionProvider"],
    "ctx_id": -1,
    "similarity_threshold": 0.5,
    "student_count": 2,
    "total_sample_count": 6,
    "loaded_embeddings": 6,
    "skipped_embeddings": []
  }
}
```

### Enroll First Student Sample

`POST /api/face/enroll`

Creates a new student. The uploaded image must contain exactly one face.

Multipart fields:

- `student_id`: required
- `student_name`: required
- `image`: required image file
- `sample_label`: optional, for example `front`

```powershell
curl.exe -X POST http://localhost:5000/api/face/enroll `
  -F "student_id=SV001" `
  -F "student_name=Nguyen Van A" `
  -F "sample_label=front" `
  -F "image=@C:\path\to\sv001_front.jpg"
```

Sample response:

```json
{
  "success": true,
  "message": "Student enrolled successfully with first face sample",
  "data": {
    "student": {
      "student_id": "SV001",
      "student_name": "Nguyen Van A",
      "created_at": "2026-04-17T08:30:00.000000+00:00",
      "updated_at": "2026-04-17T08:30:00.000000+00:00",
      "sample_count": 1
    },
    "sample": {
      "sample_id": "sample_20260417T083000000000Z_ab12cd34",
      "sample_label": "front",
      "created_at": "2026-04-17T08:30:00.000000+00:00",
      "source_image": "C:\\path\\to\\storage\\uploads\\enroll\\...",
      "face": {
        "index": 0,
        "bbox": [120.5, 80.0, 320.75, 310.25],
        "detection_score": 0.998612
      }
    },
    "face": {
      "index": 0,
      "bbox": [120.5, 80.0, 320.75, 310.25],
      "detection_score": 0.998612
    },
    "image_saved_as": "C:\\path\\to\\storage\\uploads\\enroll\\..."
  }
}
```

If the student already exists, use `/api/face/enroll-sample`.

### Enroll Additional Face Sample

`POST /api/face/enroll-sample`

Adds another face angle/sample for an existing student. The uploaded image must contain exactly one face.

Multipart fields:

- `student_id`: required
- `image`: required image file
- `sample_label`: optional, for example `left`, `right`, `up`, `down`

```powershell
curl.exe -X POST http://localhost:5000/api/face/enroll-sample `
  -F "student_id=SV001" `
  -F "sample_label=left" `
  -F "image=@C:\path\to\sv001_left.jpg"
```

Sample response:

```json
{
  "success": true,
  "message": "Face sample enrolled successfully",
  "data": {
    "student": {
      "student_id": "SV001",
      "student_name": "Nguyen Van A",
      "created_at": "2026-04-17T08:30:00.000000+00:00",
      "updated_at": "2026-04-17T08:33:00.000000+00:00",
      "sample_count": 2
    },
    "sample": {
      "sample_id": "sample_20260417T083300000000Z_ef56ab78",
      "sample_label": "left",
      "created_at": "2026-04-17T08:33:00.000000+00:00",
      "source_image": "C:\\path\\to\\storage\\uploads\\enroll-sample\\...",
      "face": {
        "index": 0,
        "bbox": [101.0, 70.0, 310.0, 305.0],
        "detection_score": 0.9979
      }
    },
    "face": {
      "index": 0,
      "bbox": [101.0, 70.0, 310.0, 305.0],
      "detection_score": 0.9979
    },
    "image_saved_as": "C:\\path\\to\\storage\\uploads\\enroll-sample\\..."
  }
}
```

### Verify Student

`POST /api/face/verify`

Compares the uploaded face against every loaded sample for the requested student and uses the maximum similarity.

Multipart fields:

- `student_id`: required
- `image`: required image file with exactly one face

```powershell
curl.exe -X POST http://localhost:5000/api/face/verify `
  -F "student_id=SV001" `
  -F "image=@C:\path\to\sv001_new_angle.jpg"
```

Sample response:

```json
{
  "success": true,
  "message": "Face verification completed",
  "data": {
    "verified": true,
    "similarity": 0.742188,
    "threshold": 0.5,
    "student": {
      "student_id": "SV001",
      "student_name": "Nguyen Van A",
      "created_at": "2026-04-17T08:30:00.000000+00:00",
      "updated_at": "2026-04-17T08:33:00.000000+00:00",
      "sample_count": 3
    },
    "sample_count": 3,
    "loaded_sample_count": 3,
    "best_sample_id": "sample_20260417T083300000000Z_ef56ab78",
    "best_sample_similarity": 0.742188,
    "best_sample": {
      "sample_id": "sample_20260417T083300000000Z_ef56ab78",
      "sample_label": "left",
      "created_at": "2026-04-17T08:33:00.000000+00:00"
    },
    "face": {
      "index": 0,
      "bbox": [100.0, 70.0, 310.0, 305.0],
      "detection_score": 0.998001
    },
    "image_saved_as": "C:\\path\\to\\storage\\uploads\\verify\\..."
  }
}
```

### Identify Faces

`POST /api/face/identify`

Detects one or more faces in an uploaded image. For each detected face, it searches all loaded samples for all students and returns the best match if it passes the threshold.

Multipart fields:

- `image`: required image file

```powershell
curl.exe -X POST http://localhost:5000/api/face/identify `
  -F "image=@C:\path\to\classroom.jpg"
```

Sample response:

```json
{
  "success": true,
  "message": "Face identification completed",
  "data": {
    "image_saved_as": "C:\\path\\to\\storage\\uploads\\identify\\...",
    "face_count": 1,
    "best_match": {
      "matched": true,
      "is_unknown": false,
      "similarity": 0.731245,
      "threshold": 0.5,
      "student": {
        "student_id": "SV001",
        "student_name": "Nguyen Van A",
        "created_at": "2026-04-17T08:30:00.000000+00:00",
        "updated_at": "2026-04-17T08:33:00.000000+00:00",
        "sample_count": 3
      },
      "best_sample_id": "sample_20260417T083300000000Z_ef56ab78",
      "best_sample_similarity": 0.731245
    },
    "detections": [
      {
        "index": 0,
        "bbox": [88.0, 44.5, 210.0, 200.0],
        "detection_score": 0.9971,
        "match": {
          "matched": true,
          "is_unknown": false,
          "similarity": 0.731245,
          "threshold": 0.5,
          "student": {
            "student_id": "SV001",
            "student_name": "Nguyen Van A",
            "created_at": "2026-04-17T08:30:00.000000+00:00",
            "updated_at": "2026-04-17T08:33:00.000000+00:00",
            "sample_count": 3
          },
          "best_sample_id": "sample_20260417T083300000000Z_ef56ab78",
          "best_sample_similarity": 0.731245
        }
      }
    ],
    "storage_warnings": []
  }
}
```

If multiple faces are found, `best_match` is `null` and each face result is in `detections`.

### Delete Student Face Data

`DELETE /api/face/students/<student_id>`

Deletes one student's metadata and all enrolled face files for that student.

Behavior:

- Removes the student record from `storage/students.json`.
- Removes all sample embedding files for that student.
- Removes legacy v1 embedding file `storage/embeddings/<student_id>.npy` if it exists.
- Removes student embedding folder `storage/embeddings/<student_id>/` if it exists.
- Removes source images referenced by that student's samples when safe:
  only when the file is inside `UPLOAD_DIR` and not referenced by another student.

```powershell
curl.exe -X DELETE http://localhost:5000/api/face/students/SV001
```

Sample response:

```json
{
  "success": true,
  "message": "Student face data deleted successfully",
  "data": {
    "student_id": "SV001",
    "student_name": "Nguyen Van A",
    "sample_count_deleted": 3,
    "deleted_embedding_files": 3,
    "deleted_image_files": 3,
    "remaining_student_count": 2,
    "remaining_total_sample_count": 4
  }
}
```

Not found response:

```json
{
  "success": false,
  "error": {
    "code": "STUDENT_NOT_FOUND",
    "message": "student_id 'SV001' was not found"
  }
}
```

Ghi chu nhanh (VI):

- Endpoint xoa toan bo du lieu khuon mat cua mot student theo `student_id`.
- Neu student khong ton tai, API tra `STUDENT_NOT_FOUND` (HTTP 404).

### Reload Embeddings

`POST /api/face/reload`

Reloads `storage/students.json` and all sample embeddings without restarting the server.

```powershell
curl.exe -X POST http://localhost:5000/api/face/reload
```

Sample response:

```json
{
  "success": true,
  "message": "Embeddings reloaded successfully",
  "data": {
    "student_count": 2,
    "total_sample_count": 6,
    "loaded_embeddings": 6,
    "skipped_embeddings": [],
    "metadata_version": 2,
    "migrated": false
  }
}
```

If old v1 metadata is found, `migrated` can be `true`; the repository rewrites metadata to v2 while preserving legacy embedding paths.

## Postman Testing

For `enroll`, `enroll-sample`, `verify`, and `identify`:

1. Select the correct HTTP method and URL.
2. Go to Body.
3. Choose `form-data`.
4. Add text fields such as `student_id`, `student_name`, `sample_label`.
5. Add `image` as type `File`.
6. Send the request.

Do not send JSON for image endpoints. They require `multipart/form-data`.

## Recommended Multi-Angle Test Flow

1. Enroll front face:

```powershell
curl.exe -X POST http://localhost:5000/api/face/enroll `
  -F "student_id=SV001" `
  -F "student_name=Nguyen Van A" `
  -F "sample_label=front" `
  -F "image=@C:\path\to\sv001_front.jpg"
```

2. Enroll left angle:

```powershell
curl.exe -X POST http://localhost:5000/api/face/enroll-sample `
  -F "student_id=SV001" `
  -F "sample_label=left" `
  -F "image=@C:\path\to\sv001_left.jpg"
```

3. Enroll right angle:

```powershell
curl.exe -X POST http://localhost:5000/api/face/enroll-sample `
  -F "student_id=SV001" `
  -F "sample_label=right" `
  -F "image=@C:\path\to\sv001_right.jpg"
```

4. Verify a new angle:

```powershell
curl.exe -X POST http://localhost:5000/api/face/verify `
  -F "student_id=SV001" `
  -F "image=@C:\path\to\sv001_new_angle.jpg"
```

5. Identify from a classroom or group image:

```powershell
curl.exe -X POST http://localhost:5000/api/face/identify `
  -F "image=@C:\path\to\classroom.jpg"
```

6. Reload from disk:

```powershell
curl.exe -X POST http://localhost:5000/api/face/reload
```

## Image Requirements

- `enroll`, `enroll-sample`, and `verify` require exactly one detected face.
- `identify` supports one or multiple detected faces.
- Allowed extensions default to `jpg`, `jpeg`, `png`, `webp`.
- Avoid dark, blurry, heavily occluded, or very small faces.
- For multi-angle enrollment, use clear front, left, and right samples.

## React Frontend Integration

Use `FormData` and the field name `image`. Do not manually set the `Content-Type` header.

Enroll:

```javascript
const formData = new FormData();
formData.append("student_id", "SV001");
formData.append("student_name", "Nguyen Van A");
formData.append("sample_label", "front");
formData.append("image", imageFile);

const response = await fetch("http://localhost:5000/api/face/enroll", {
  method: "POST",
  body: formData,
});
const result = await response.json();
```

Add sample:

```javascript
const formData = new FormData();
formData.append("student_id", "SV001");
formData.append("sample_label", "left");
formData.append("image", imageFile);

const response = await fetch("http://localhost:5000/api/face/enroll-sample", {
  method: "POST",
  body: formData,
});
const result = await response.json();
```

Verify:

```javascript
const formData = new FormData();
formData.append("student_id", "SV001");
formData.append("image", imageFile);

const response = await fetch("http://localhost:5000/api/face/verify", {
  method: "POST",
  body: formData,
});
const result = await response.json();
```

Identify:

```javascript
const formData = new FormData();
formData.append("image", imageFile);

const response = await fetch("http://localhost:5000/api/face/identify", {
  method: "POST",
  body: formData,
});
const result = await response.json();
```

Set `CORS_ORIGINS` in `.env` to include your frontend origin, for example `http://localhost:5173`.

## Common Errors

- `NO_FILE_UPLOADED`: missing `image` file field.
- `INVALID_FILE_TYPE`: image extension is not allowed.
- `NO_FACE_FOUND`: InsightFace did not detect a face.
- `MULTIPLE_FACES_FOUND`: endpoint requires exactly one face but the image has more than one.
- `STUDENT_ALREADY_EXISTS`: use `/api/face/enroll-sample` for an existing student.
- `STUDENT_NOT_FOUND`: enroll the student first.
- `CORRUPTED_STORED_EMBEDDING`: no valid embedding samples could be loaded for that student.
- `MODEL_INITIALIZATION_FAILED`: InsightFace could not initialize.

## Data Reset for Local Testing

You can now delete one student safely with:

```powershell
curl.exe -X DELETE http://localhost:5000/api/face/students/<student_id>
```

For full local reset only, stop the server first, then carefully remove or edit:

```text
storage/students.json
storage/embeddings/
storage/uploads/
```

Keep `.gitkeep` files if you want the empty folders to remain in Git.
