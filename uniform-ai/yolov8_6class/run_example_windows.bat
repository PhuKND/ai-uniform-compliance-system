@echo off
REM Image inference
python infer_windows.py --source demo_image.jpg --weights best.pt --conf 0.25

REM Folder inference
python infer_windows.py --source demo_images --weights best.pt --conf 0.25

REM Video inference
python infer_windows.py --source demo_video.mp4 --weights best.pt --conf 0.25

REM Webcam inference
python infer_windows.py --source 0 --weights best.pt --conf 0.25

pause
