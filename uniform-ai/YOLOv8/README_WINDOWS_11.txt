HUONG DAN GOI YOLOV8 TRONG UNIFORM-AI

Goi YOLOv8 nay khong chay thanh server Flask rieng trong phien ban tich hop.
Hay chay server goc cua du an:

    cd path\to\uniform-ai
    python app.py

Server goc se tu dong nap YOLOv8 tu thu muc:

    YOLOv8/

File weight uu tien:

    best.pt

Neu thieu best.pt, server thu fallback:

    last.pt

Bon lop chinh thuc:

    0: ao_so_mi_trang       = ao so mi trang
    1: ao_doan_thanh_nien   = ao Doan Thanh nien mau xanh
    2: quan_tay_dai_den     = quan tay dai den
    3: khan_quang_do        = khan quang do

Kiem tra health:

    http://127.0.0.1:5001/api/uniform/health

API YOLOv8-only:

    POST http://127.0.0.1:5001/api/uniform/yolov8/predict

Form-data:

    image: file anh
    confidence: tuy chon, mac dinh 0.25
    image_size: tuy chon, mac dinh 640
    save_annotated: tuy chon, true/false

Lenh PowerShell:

    Invoke-RestMethod -Method Post `
      -Uri "http://127.0.0.1:5001/api/uniform/yolov8/predict" `
      -Form @{
        image = Get-Item ".\test.jpg"
        confidence = "0.25"
        image_size = "640"
        save_annotated = "true"
      } | ConvertTo-Json -Depth 12

Neu muon chay inference bang dong lenh truc tiep trong thu muc YOLOv8:

    python infer_windows.py --source test.jpg --weights best.pt --conf 0.25

Khong copy app Flask cu cua YOLO de ghi de len file:

    uniform-ai/app.py

Neu can giu app cu, dat ten:

    YOLOv8/yolov8_legacy_app.py
