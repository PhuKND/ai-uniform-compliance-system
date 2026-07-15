# School Uniform YOLOv8 Detector - Windows 11 64-bit

## Supported System
Windows 11 64-bit.

## Python
Use Python 3.10 or Python 3.11 64-bit.

## Create and Activate a Virtual Environment
```bat
python -m venv .venv
.venv\Scripts\activate
```

## Install Dependencies
```bat
python -m pip install --upgrade pip
pip install -r requirements_windows.txt
```

## Image Inference
```bat
python infer_windows.py --source demo_image.jpg --weights best.pt --conf 0.25
```

## Folder Inference
```bat
python infer_windows.py --source demo_images --weights best.pt --conf 0.25
```

## Video Inference
```bat
python infer_windows.py --source demo_video.mp4 --weights best.pt --conf 0.25
```

## Webcam Inference
```bat
python infer_windows.py --source 0 --weights best.pt --conf 0.25
```

## Default Output Folder
Annotated outputs are saved under `runs_uniform_predict/predict`, unless `--output` is changed.

## Class Mapping
0: ao_so_mi_trang
1: ao_doan_thanh_nien
2: quan_tay_dai_den
3: khan_quang_do
4: quan_short_tay_den
5: quan_dai_trang

## Model Files
`best.pt` is the checkpoint with the best validation fitness and is recommended for inference.

`last.pt` is included and can be used for continuation or audit.

## Model Provenance
The detector was fine-tuned from `yolov8s.pt` using the Ultralytics YOLOv8 framework. It was not implemented from scratch.

## Package Notes
- `data.yaml` contains only train/validation metadata and has no test key.
- Raw source images and original source labels are intentionally excluded.
- See `checkpoint_status.json` and `windows_package_build_report.json` for diagnostics.
