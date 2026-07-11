import {
  IconArrowRight as ArrowRight,
  IconCamera as Camera,
  IconDeviceDesktopUp as MonitorUp,
  IconPhotoPlus as ImagePlus,
  IconRefresh as RefreshCw,
  IconUpload as UploadCloud,
  IconX as X,
} from "@tabler/icons-react";
import { FormEvent, MutableRefObject, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { runAdvancedEvaluation, runLightweightEvaluation, type AdvancedEvaluationMethod } from "../api/evaluations";
import { AuthenticatedImage } from "../components/AuthenticatedImage";
import type { EvaluationCompareResponse } from "../types";
import { compressImageBeforeUpload, formatBytes } from "../utils/imageCompression";

const LATEST_COMPARISON_KEY = "uniform_latest_comparison";
const DEFAULT_CAMERA_MODE: CameraFacingMode = "environment";
const ADVANCED_METHODS: Array<{
  key: AdvancedEvaluationMethod;
  label: string;
  shortLabel: string;
  description: string;
}> = [
  {
    key: "YOLOV8_V2",
    label: "YOLOv8 V2 (6 lớp)",
    shortLabel: "YOLOv8 V2",
    description: "Nhanh, dùng best.pt mới và lọc trùng theo từng lớp.",
  },
  {
    key: "GROUNDING_DINO_V2",
    label: "Grounding DINO V2",
    shortLabel: "Grounding DINO V2",
    description: "Chạy sáu prompt đồng phục để đối chiếu kỹ hơn.",
  },
];

type ImageSource = "file" | "camera";
type CameraFacingMode = "environment" | "user";
type EvaluationMode = "lightweight" | "advanced";

const CAMERA_MODE_LABELS: Record<CameraFacingMode, string> = {
  environment: "Camera sau",
  user: "Camera trước",
};

interface StartCameraOptions {
  allowFallback?: boolean;
}

export function saveLatestComparison(result: EvaluationCompareResponse) {
  sessionStorage.setItem(LATEST_COMPARISON_KEY, JSON.stringify(result));
}

export function readLatestComparison() {
  const raw = sessionStorage.getItem(LATEST_COMPARISON_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as EvaluationCompareResponse;
  } catch {
    return null;
  }
}

export function UploadPage() {
  const [studentCode, setStudentCode] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [imageSource, setImageSource] = useState<ImageSource | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [compressionNote, setCompressionNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [evaluationMode, setEvaluationMode] = useState<EvaluationMode>("advanced");
  const [selectedMethod, setSelectedMethod] = useState<AdvancedEvaluationMethod>("YOLOV8_V2");
  const [cameraOpen, setCameraOpen] = useState(false);
  const [cameraStarting, setCameraStarting] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [cameraNotice, setCameraNotice] = useState<string | null>(null);
  const [cameraFacingMode, setCameraFacingMode] = useState<CameraFacingMode>(DEFAULT_CAMERA_MODE);
  const [availableVideoDevices, setAvailableVideoDevices] = useState<MediaDeviceInfo[]>([]);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const cameraRequestIdRef = useRef(0);
  const mountedRef = useRef(true);
  const navigate = useNavigate();

  const fileName = useMemo(() => file?.name ?? "Chưa chọn ảnh", [file]);
  const selectedMethodInfo = useMemo(
    () => ADVANCED_METHODS.find((method) => method.key === selectedMethod) ?? ADVANCED_METHODS[0],
    [selectedMethod],
  );
  const effectiveSubmitLabel =
    evaluationMode === "lightweight"
      ? "Chạy đánh giá nhanh không SCHP/FLORENCE"
      : `Chạy ${selectedMethodInfo.shortLabel}`;
  const cameraSelected = imageSource === "camera";
  const hasSingleCamera = availableVideoDevices.length === 1;

  const stopCameraStream = useCallback(() => {
    stopMediaStream(streamRef.current);
    streamRef.current = null;
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
  }, []);

  const refreshVideoDevices = useCallback(async () => {
    if (!navigator.mediaDevices?.enumerateDevices) return [];
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      const videoDevices = devices.filter((device) => device.kind === "videoinput");
      if (mountedRef.current) {
        setAvailableVideoDevices(videoDevices);
      }
      return videoDevices;
    } catch {
      return [];
    }
  }, []);

  const startCamera = useCallback(
    async (preferredMode: CameraFacingMode, options: StartCameraOptions = {}) => {
      const requestId = cameraRequestIdRef.current + 1;
      cameraRequestIdRef.current = requestId;

      if (!navigator.mediaDevices?.getUserMedia) {
        setCameraError("Trình duyệt không hỗ trợ camera. Vui lòng dùng HTTPS, localhost hoặc chọn ảnh từ máy.");
        return false;
      }

      if (!window.isSecureContext) {
        setCameraError("Camera chỉ hoạt động trên HTTPS hoặc localhost. Vui lòng mở trang bằng kết nối bảo mật.");
        return false;
      }

      stopCameraStream();
      setCameraStarting(true);
      setCameraError(null);
      setCameraNotice(null);

      const modesToTry = options.allowFallback === false ? [preferredMode] : [preferredMode, oppositeCameraMode(preferredMode)];
      let lastError: unknown = null;

      for (const mode of modesToTry) {
        try {
          const knownDevices = await refreshVideoDevices();
          const initialStream = await requestStreamForMode(mode, knownDevices);
          if (!isActiveCameraRequest(requestId, cameraRequestIdRef, mountedRef)) {
            stopMediaStream(initialStream);
            return false;
          }

          const devicesAfterPermission = await refreshVideoDevices();
          const stream = await preferExactDeviceIfPossible(initialStream, mode, devicesAfterPermission);
          if (!isActiveCameraRequest(requestId, cameraRequestIdRef, mountedRef)) {
            stopMediaStream(stream);
            return false;
          }

          const actualMode = inferFacingMode(stream, mode, devicesAfterPermission);
          streamRef.current = stream;
          if (videoRef.current) {
            videoRef.current.srcObject = stream;
            await videoRef.current.play();
          }

          if (!isActiveCameraRequest(requestId, cameraRequestIdRef, mountedRef)) {
            stopMediaStream(stream);
            return false;
          }

          setCameraFacingMode(actualMode);
          if (actualMode !== preferredMode) {
            setCameraNotice(`Không mở được ${CAMERA_MODE_LABELS[preferredMode].toLowerCase()}, đã chuyển sang ${CAMERA_MODE_LABELS[actualMode].toLowerCase()}.`);
          } else if (mode !== preferredMode) {
            setCameraNotice(`Không mở được ${CAMERA_MODE_LABELS[preferredMode].toLowerCase()}, đã chuyển sang ${CAMERA_MODE_LABELS[actualMode].toLowerCase()}.`);
          }
          setCameraStarting(false);
          return true;
        } catch (err) {
          lastError = err;
          stopCameraStream();
        }
      }

      if (isActiveCameraRequest(requestId, cameraRequestIdRef, mountedRef)) {
        setCameraError(toFriendlyCameraError(lastError));
        setCameraStarting(false);
      }
      return false;
    },
    [refreshVideoDevices, stopCameraStream],
  );

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      cameraRequestIdRef.current += 1;
      stopCameraStream();
    };
  }, [stopCameraStream]);

  useEffect(() => {
    if (!cameraOpen) return undefined;
    void startCamera(DEFAULT_CAMERA_MODE, { allowFallback: true });
    return () => {
      cameraRequestIdRef.current += 1;
      stopCameraStream();
    };
  }, [cameraOpen, startCamera, stopCameraStream]);

  useEffect(() => {
    return () => {
      if (previewUrl) URL.revokeObjectURL(previewUrl);
    };
  }, [previewUrl]);

  function pickFile(nextFile: File | null, source: ImageSource) {
    setFile(nextFile);
    setImageSource(nextFile ? source : null);
    setCompressionNote(null);
    setError(null);
    setPreviewUrl(nextFile ? URL.createObjectURL(nextFile) : null);
  }

  function handleFileSelection(nextFile?: File) {
    if (!nextFile) return;
    closeCamera();
    pickFile(nextFile, "file");
  }

  function openCamera() {
    setCameraFacingMode(DEFAULT_CAMERA_MODE);
    setCameraOpen(true);
    setCameraError(null);
    setCameraNotice(null);
    setError(null);
  }

  function closeCamera() {
    cameraRequestIdRef.current += 1;
    stopCameraStream();
    setCameraOpen(false);
    setCameraStarting(false);
    setCameraError(null);
    setCameraNotice(null);
  }

  async function switchCamera() {
    const previousMode = cameraFacingMode;
    const targetMode = oppositeCameraMode(previousMode);
    const switched = await startCamera(targetMode, { allowFallback: false });

    if (!switched) {
      const restored = await startCamera(previousMode, { allowFallback: false });
      if (restored && mountedRef.current) {
        setCameraError(null);
        setCameraNotice(`Không thể đổi sang ${CAMERA_MODE_LABELS[targetMode].toLowerCase()}. Đã giữ ${CAMERA_MODE_LABELS[previousMode].toLowerCase()}.`);
      }
    }
  }

  async function capturePhoto() {
    const video = videoRef.current;
    if (!video || video.videoWidth === 0 || video.videoHeight === 0) {
      setCameraError("Camera chưa sẵn sàng. Vui lòng đợi vài giây rồi chụp lại.");
      return;
    }

    const canvas = document.createElement("canvas");
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const context = canvas.getContext("2d");
    if (!context) {
      setCameraError("Không thể chụp ảnh từ camera. Vui lòng thử lại hoặc tải ảnh từ máy.");
      return;
    }

    if (cameraFacingMode === "user") {
      context.translate(canvas.width, 0);
      context.scale(-1, 1);
    }
    context.drawImage(video, 0, 0, canvas.width, canvas.height);

    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.92));
    if (!blob) {
      setCameraError("Không thể tạo ảnh từ camera. Vui lòng thử lại.");
      return;
    }

    const capturedFile = new File([blob], `camera-capture-${Date.now()}.jpg`, { type: "image/jpeg" });
    pickFile(capturedFile, "camera");
    setCompressionNote("Đã chụp ảnh từ camera. Ảnh sẽ được nén trước khi gửi đánh giá.");
    closeCamera();
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!file) {
      setError("Vui lòng chọn ảnh hoặc chụp ảnh trước khi đánh giá.");
      return;
    }

    setSubmitting(true);
    setError(null);
    setCompressionNote("Đang nén ảnh trước khi gửi...");
    try {
      const compressed = await compressImageBeforeUpload(file);
      setCompressionNote(
        compressed.compressed
          ? `Đã nén ảnh từ ${formatBytes(compressed.originalBytes)} xuống ${formatBytes(compressed.finalBytes)}.`
          : `Ảnh đạt yêu cầu: ${formatBytes(compressed.finalBytes)}.`,
      );
      const result =
        evaluationMode === "lightweight"
          ? await runLightweightEvaluation(compressed.file, studentCode)
          : await runAdvancedEvaluation(compressed.file, selectedMethod, studentCode);
      saveLatestComparison(result);
      navigate("/compare", { state: { result } });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể đánh giá ảnh.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="page-content upload-layout">
      <section className="upload-panel">
        <div>
          <p className="eyebrow">Đánh giá mới</p>
          <h2>Tải ảnh đồng phục</h2>
          <p className="muted">
            Ảnh sẽ được nén dưới 1 MB tại trình duyệt rồi gửi đến backend để chạy chế độ đánh giá AI đã chọn.
          </p>
        </div>

        <form className="form-stack" onSubmit={submit}>
          <label>
            Mã học sinh
            <input
              placeholder="Có thể bỏ trống để AI tự nhận diện"
              value={studentCode}
              onChange={(event) => setStudentCode(event.target.value)}
            />
          </label>

          <div className="method-selector" aria-label="Chọn kiểu đánh giá">
            <button
              className={`method-option ${evaluationMode === "lightweight" ? "active" : ""}`}
              type="button"
              onClick={() => setEvaluationMode("lightweight")}
              disabled={submitting}
            >
              <strong>Đánh giá nhanh không dùng SCHP/FLORENCE</strong>
              <span>Chạy thêm 2 phương pháp mới bằng Pose + InsightFace + detector, chỉ kiểm tra thành phần đồng phục và bỏ qua SCHP/FLORENCE.</span>
            </button>
            <button
              className={`method-option ${evaluationMode === "advanced" ? "active" : ""}`}
              type="button"
              onClick={() => setEvaluationMode("advanced")}
              disabled={submitting}
            >
              <strong>Đánh giá v2 đầy đủ từng phương pháp</strong>
              <span>Giữ đầy đủ luồng nhưng sẽ mất nhiều thời gian xử lý.</span>
            </button>
          </div>

          {evaluationMode === "lightweight" ? (
            <div className="lightweight-method-summary">
              <div>
                <strong>Phương pháp 1: Pose + InsightFace + Grounding DINO</strong>
                <span>YOLOv8 Pose chọn học sinh, InsightFace xác thực/nhận diện, Grounding DINO phát hiện thành phần đồng phục.</span>
              </div>
              <div>
                <strong>Phương pháp 2: Pose + InsightFace + YOLOv8 đồng phục</strong>
                <span>YOLOv8 Pose chọn học sinh, InsightFace xác thực/nhận diện, model YOLOv8 đồng phục phát hiện thành phần bắt buộc.</span>
              </div>
            </div>
          ) : (
            <div className="method-selector" aria-label="Chọn phương pháp đánh giá v2 đầy đủ">
              {ADVANCED_METHODS.map((method) => (
                <button
                  className={`method-option ${selectedMethod === method.key ? "active" : ""}`}
                  type="button"
                  key={method.key}
                  onClick={() => setSelectedMethod(method.key)}
                  disabled={submitting}
                >
                  <strong>{method.label}</strong>
                  <span>{method.description}</span>
                </button>
              ))}
            </div>
          )}

          <div className="capture-mode-grid" aria-label="Chọn nguồn ảnh">
            <label className={`capture-source ${imageSource === "file" ? "active" : ""}`}>
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                onChange={(event) => {
                  handleFileSelection(event.currentTarget.files?.[0]);
                  event.currentTarget.value = "";
                }}
              />
              <MonitorUp size={22} />
              <span>Tải ảnh từ máy</span>
              <small>JPG, PNG hoặc WEBP</small>
            </label>

            <div className={`capture-source ${cameraOpen || cameraSelected ? "active" : ""}`}>
              <Camera size={22} />
              <span>Chụp bằng camera</span>
              <small>{cameraSelected ? "Ảnh vừa chụp đã sẵn sàng" : "Ưu tiên camera sau trên điện thoại"}</small>
              <button className="button secondary small-button" type="button" onClick={openCamera} disabled={submitting}>
                {cameraSelected ? <RefreshCw size={15} /> : <Camera size={15} />}
                {cameraSelected ? "Chụp lại" : "Mở camera"}
              </button>
            </div>
          </div>

          {cameraOpen ? (
            <div className="camera-panel">
              <div className="camera-panel-header">
                <div>
                  <strong>Camera</strong>
                  <span>Đang dùng: {CAMERA_MODE_LABELS[cameraFacingMode]}</span>
                </div>
                <button className="icon-button" type="button" onClick={closeCamera} aria-label="Đóng camera">
                  <X size={17} />
                </button>
              </div>
              <div className="camera-frame">
                <video
                  ref={videoRef}
                  className={cameraFacingMode === "user" ? "mirror" : undefined}
                  autoPlay
                  playsInline
                  muted
                />
                {cameraStarting ? <div className="camera-overlay">Đang mở camera...</div> : null}
              </div>

              <div className="camera-switch-row">
                <button
                  className="button secondary full-width"
                  type="button"
                  onClick={() => void switchCamera()}
                  disabled={cameraStarting || hasSingleCamera}
                >
                  <RefreshCw size={17} />
                  Đổi camera
                </button>
                <span>{hasSingleCamera ? "Thiết bị hiện chỉ báo một camera." : `Chuyển sang ${CAMERA_MODE_LABELS[oppositeCameraMode(cameraFacingMode)].toLowerCase()}`}</span>
              </div>

              {cameraNotice ? <div className="alert success">{cameraNotice}</div> : null}
              {cameraError ? <div className="alert danger">{cameraError}</div> : null}
              <div className="camera-actions">
                <button className="button primary" type="button" onClick={() => void capturePhoto()} disabled={cameraStarting || Boolean(cameraError)}>
                  <Camera size={17} />
                  Chụp ảnh
                </button>
                <button className="button ghost" type="button" onClick={closeCamera}>
                  Đóng camera
                </button>
              </div>
            </div>
          ) : null}

          <label className="drop-zone">
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp"
              onChange={(event) => {
                handleFileSelection(event.currentTarget.files?.[0]);
                event.currentTarget.value = "";
              }}
            />
            <ImagePlus size={34} />
            <strong>{fileName}</strong>
            <span>{cameraSelected ? "Ảnh chụp từ camera" : "Chọn ảnh JPG, PNG hoặc WEBP"}</span>
          </label>

          {compressionNote ? <div className="alert success">{compressionNote}</div> : null}
          {error ? <div className="alert danger">{error}</div> : null}

          <button className="button primary full-width" type="submit" disabled={submitting || !file}>
            <UploadCloud size={18} />
            {submitting ? "Đang đánh giá..." : effectiveSubmitLabel}
          </button>
        </form>
      </section>

      <section className="preview-panel">
        <div className="panel-header">
          <h3>Ảnh đầu vào</h3>
          <ArrowRight size={18} />
        </div>
        <div className="media-frame tall upload-preview-frame">
          {previewUrl ? (
            <img className="upload-preview-image" src={previewUrl} alt="Ảnh được chọn" />
          ) : (
            <AuthenticatedImage src={null} alt="Chưa chọn ảnh" />
          )}
        </div>
      </section>
    </div>
  );
}

async function requestStreamForMode(mode: CameraFacingMode, devices: MediaDeviceInfo[]) {
  const constraints: MediaStreamConstraints[] = [
    { audio: false, video: { facingMode: { ideal: mode } } },
  ];

  const matchingDevice = findDeviceForFacingMode(devices, mode);
  if (matchingDevice?.deviceId) {
    constraints.push({ audio: false, video: { deviceId: { exact: matchingDevice.deviceId } } });
  }

  constraints.push({ audio: false, video: true });

  let lastError: unknown = null;
  for (const constraint of constraints) {
    try {
      return await navigator.mediaDevices.getUserMedia(constraint);
    } catch (err) {
      lastError = err;
    }
  }
  throw lastError;
}

async function preferExactDeviceIfPossible(stream: MediaStream, mode: CameraFacingMode, devices: MediaDeviceInfo[]) {
  const preferredDevice = findDeviceForFacingMode(devices, mode);
  const currentDeviceId = getStreamDeviceId(stream);

  if (!preferredDevice?.deviceId || preferredDevice.deviceId === currentDeviceId) {
    return stream;
  }

  try {
    const exactStream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { deviceId: { exact: preferredDevice.deviceId } },
    });
    stopMediaStream(stream);
    return exactStream;
  } catch {
    return stream;
  }
}

function inferFacingMode(stream: MediaStream, requestedMode: CameraFacingMode, devices: MediaDeviceInfo[]): CameraFacingMode {
  const track = stream.getVideoTracks()[0];
  const facingMode = track?.getSettings().facingMode;
  if (facingMode === "environment" || facingMode === "user") {
    return facingMode;
  }

  const deviceId = getStreamDeviceId(stream);
  const currentDevice = devices.find((device) => device.deviceId === deviceId);
  if (currentDevice && deviceMatchesFacingMode(currentDevice, "environment")) return "environment";
  if (currentDevice && deviceMatchesFacingMode(currentDevice, "user")) return "user";
  return requestedMode;
}

function findDeviceForFacingMode(devices: MediaDeviceInfo[], mode: CameraFacingMode) {
  const labeledMatch = devices.find((device) => deviceMatchesFacingMode(device, mode));
  if (labeledMatch) return labeledMatch;
  if (devices.length <= 1) return null;
  return mode === "environment" ? devices[devices.length - 1] : devices[0];
}

function deviceMatchesFacingMode(device: MediaDeviceInfo, mode: CameraFacingMode) {
  const label = normalizeDeviceLabel(device.label);
  if (!label) return false;
  const rearHints = ["back", "rear", "environment", "world", "sau", "mat sau", "camera sau", "facing back"];
  const frontHints = ["front", "user", "face", "facetime", "selfie", "truoc", "mat truoc", "camera truoc", "facing front"];
  const hints = mode === "environment" ? rearHints : frontHints;
  return hints.some((hint) => label.includes(hint));
}

function normalizeDeviceLabel(label: string) {
  return label
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "");
}

function getStreamDeviceId(stream: MediaStream) {
  return stream.getVideoTracks()[0]?.getSettings().deviceId;
}

function oppositeCameraMode(mode: CameraFacingMode): CameraFacingMode {
  return mode === "environment" ? "user" : "environment";
}

function stopMediaStream(stream?: MediaStream | null) {
  stream?.getTracks().forEach((track) => track.stop());
}

function isActiveCameraRequest(
  requestId: number,
  requestIdRef: MutableRefObject<number>,
  mountedRef: MutableRefObject<boolean>,
) {
  return mountedRef.current && requestId === requestIdRef.current;
}

function toFriendlyCameraError(error: unknown) {
  if (error instanceof DOMException) {
    if (error.name === "NotAllowedError" || error.name === "SecurityError") {
      return "Bạn chưa cấp quyền camera. Vui lòng cho phép camera hoặc tải ảnh từ máy.";
    }
    if (error.name === "NotFoundError" || error.name === "DevicesNotFoundError") {
      return "Không tìm thấy camera trên thiết bị này. Vui lòng tải ảnh từ máy.";
    }
    if (error.name === "NotReadableError" || error.name === "TrackStartError") {
      return "Camera đang được ứng dụng khác sử dụng. Vui lòng đóng ứng dụng đó rồi thử lại.";
    }
    if (error.name === "OverconstrainedError" || error.name === "ConstraintNotSatisfiedError") {
      return "Camera được chọn không khả dụng trên thiết bị này. Vui lòng đổi camera hoặc tải ảnh từ máy.";
    }
    if (error.name === "AbortError") {
      return "Trình duyệt đã ngắt kết nối camera. Vui lòng mở lại camera và thử lại.";
    }
  }
  return "Không thể mở camera. Vui lòng kiểm tra quyền truy cập hoặc tải ảnh từ máy.";
}
