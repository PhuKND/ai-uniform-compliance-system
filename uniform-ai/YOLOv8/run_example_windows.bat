@echo off
REM Example: predict one image
python infer_windows.py --source test.jpg --weights best.pt --conf 0.25

REM Example: predict from webcam index 0
python infer_windows.py --source 0 --weights best.pt --conf 0.25
pause
