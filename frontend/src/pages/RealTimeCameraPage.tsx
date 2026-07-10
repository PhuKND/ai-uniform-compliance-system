import {
  IconActivity as Activity,
  IconCamera as Camera,
  IconCameraOff as CameraOff,
  IconPlayerPause as Pause,
  IconPlayerPlay as Play,
  IconRefresh as RefreshCw,
  IconUserCheck as UserCheck,
} from "@tabler/icons-react";
import { MutableRefObject, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { analyzeRealtimeCameraFrame } from "../api/realtimeCamera";
import type {
  RealtimeCameraAnalysis,
  RealtimeCameraKeypoint,
  RealtimeUniformDetection,
} from "../types";
import { asPercent } from "../utils/format";

const FRAME_WIDTH = 540;
const FRAME_HEIGHT = 960;
const ANALYSIS_INTERVAL_MS = 220;
const DEFAULT_CAMERA_MODE: CameraFacingMode = "environment";
const MIN_KEYPOINT_CONFIDENCE = 0.2;

type CameraFacingMode = "environment" | "user";

const CAMERA_MODE_LABELS: Record<CameraFacingMode, string> = {
  environment: "Rear camera",
  user: "Front camera",
};

interface StartCameraOptions {
  allowFallback?: boolean;
}

export function RealTimeCameraPage() {
  const [cameraActive, setCameraActive] = useState(false);
  const [cameraStarting, setCameraStarting] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [cameraNotice, setCameraNotice] = useState<string | null>(null);
  const [cameraFacingMode, setCameraFacingMode] = useState<CameraFacingMode>(DEFAULT_CAMERA_MODE);
  const [availableVideoDevices, setAvailableVideoDevices] = useState<MediaDeviceInfo[]>([]);
  const [analysisPaused, setAnalysisPaused] = useState(false);
  const [analysisStatus, setAnalysisStatus] = useState("Idle");
  const [analysisError, setAnalysisError] = useState<string | null>(null);
  const [latestResult, setLatestResult] = useState<RealtimeCameraAnalysis | null>(null);
  const [fps, setFps] = useState<number | null>(null);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const overlayCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const captureCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const analysisTimerRef = useRef<number | null>(null);
  const inFlightRef = useRef(false);
  const cameraRequestIdRef = useRef(0);
  const mountedRef = useRef(true);

  const cameraReady = cameraActive && !cameraStarting && !cameraError;
  const hasSingleCamera = availableVideoDevices.length === 1;
  const identity = latestResult?.identity;
  const uniformDetections = latestResult?.uniformDetections ?? [];
  const selectedPersonConfidence = latestResult?.selectedPerson?.confidence;

  const statusTone = useMemo(() => {
    if (cameraError || analysisError) return "danger";
    if (cameraReady && !analysisPaused) return "success";
    return "neutral";
  }, [analysisError, analysisPaused, cameraError, cameraReady]);

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
        setCameraError("This browser does not support camera access.");
        return false;
      }

      if (!window.isSecureContext) {
        setCameraError("Camera access requires HTTPS or localhost.");
        return false;
      }

      stopCameraStream();
      clearOverlay(overlayCanvasRef.current);
      setCameraStarting(true);
      setCameraError(null);
      setCameraNotice(null);
      setAnalysisError(null);
      setLatestResult(null);

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
          setCameraActive(true);
          setCameraStarting(false);
          setAnalysisPaused(false);
          setAnalysisStatus("Live");
          if (actualMode !== preferredMode || mode !== preferredMode) {
            setCameraNotice(`Using ${CAMERA_MODE_LABELS[actualMode].toLowerCase()} because the requested camera was unavailable.`);
          }
          return true;
        } catch (err) {
          lastError = err;
          stopCameraStream();
        }
      }

      if (isActiveCameraRequest(requestId, cameraRequestIdRef, mountedRef)) {
        setCameraError(toFriendlyCameraError(lastError));
        setCameraStarting(false);
        setCameraActive(false);
      }
      return false;
    },
    [refreshVideoDevices, stopCameraStream],
  );

  const analyzeFrame = useCallback(async () => {
    if (inFlightRef.current || analysisPaused) return;
    const video = videoRef.current;
    if (!video || video.videoWidth === 0 || video.videoHeight === 0) {
      setAnalysisStatus("Waiting for camera");
      return;
    }

    const captureCanvas = captureCanvasRef.current ?? document.createElement("canvas");
    captureCanvasRef.current = captureCanvas;
    captureCanvas.width = FRAME_WIDTH;
    captureCanvas.height = FRAME_HEIGHT;
    const context = captureCanvas.getContext("2d", { alpha: false });
    if (!context) {
      setAnalysisError("Canvas is not available in this browser.");
      return;
    }

    inFlightRef.current = true;
    setAnalysisStatus("Analyzing");
    try {
      drawVideoCover(video, context, FRAME_WIDTH, FRAME_HEIGHT, cameraFacingMode === "user");
      const blob = await canvasToBlob(captureCanvas, "image/jpeg", 0.72);
      const started = performance.now();
      const result = await analyzeRealtimeCameraFrame(blob, FRAME_WIDTH, FRAME_HEIGHT);
      const elapsed = performance.now() - started;
      if (!mountedRef.current) return;

      setLatestResult(result);
      drawOverlay(overlayCanvasRef.current, result);
      setAnalysisStatus(result.success ? "Live" : "Model unavailable");
      setAnalysisError(result.success ? null : result.message);
      setFps(elapsed > 0 ? Math.min(60, 1000 / elapsed) : null);
    } catch (err) {
      if (!mountedRef.current) return;
      setAnalysisStatus("Server error");
      setAnalysisError(err instanceof Error ? err.message : "Cannot analyze camera frame.");
    } finally {
      inFlightRef.current = false;
    }
  }, [analysisPaused, cameraFacingMode]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      cameraRequestIdRef.current += 1;
      stopCameraStream();
      clearAnalysisTimer(analysisTimerRef);
    };
  }, [stopCameraStream]);

  useEffect(() => {
    if (!cameraReady || analysisPaused) {
      clearAnalysisTimer(analysisTimerRef);
      return undefined;
    }

    let cancelled = false;
    async function loop() {
      await analyzeFrame();
      if (!cancelled) {
        analysisTimerRef.current = window.setTimeout(loop, ANALYSIS_INTERVAL_MS);
      }
    }

    analysisTimerRef.current = window.setTimeout(loop, 160);
    return () => {
      cancelled = true;
      clearAnalysisTimer(analysisTimerRef);
    };
  }, [analyzeFrame, analysisPaused, cameraReady]);

  useEffect(() => {
    if (!cameraActive) {
      clearOverlay(overlayCanvasRef.current);
    }
  }, [cameraActive]);

  function openCamera() {
    void startCamera(DEFAULT_CAMERA_MODE, { allowFallback: true });
  }

  function stopCamera() {
    cameraRequestIdRef.current += 1;
    clearAnalysisTimer(analysisTimerRef);
    stopCameraStream();
    clearOverlay(overlayCanvasRef.current);
    setCameraActive(false);
    setCameraStarting(false);
    setAnalysisStatus("Stopped");
    setAnalysisError(null);
    setLatestResult(null);
    setFps(null);
  }

  async function switchCamera() {
    const previousMode = cameraFacingMode;
    const targetMode = oppositeCameraMode(previousMode);
    const switched = await startCamera(targetMode, { allowFallback: false });
    if (!switched) {
      const restored = await startCamera(previousMode, { allowFallback: false });
      if (restored && mountedRef.current) {
        setCameraError(null);
        setCameraNotice(`Could not switch to ${CAMERA_MODE_LABELS[targetMode].toLowerCase()}. Keeping ${CAMERA_MODE_LABELS[previousMode].toLowerCase()}.`);
      }
    }
  }

  return (
    <div className="page-content realtime-camera-grid">
      <section className="realtime-stage-panel">
        <div className="panel-header">
          <div>
            <p className="eyebrow">Fast pipeline</p>
            <h2>Real-time Camera</h2>
          </div>
          <span className={`badge ${statusTone}`}>
            <Activity size={14} />
            {analysisPaused ? "Paused" : analysisStatus}
          </span>
        </div>

        <div className="realtime-stage">
          <video
            ref={videoRef}
            className={cameraFacingMode === "user" ? "mirror" : undefined}
            autoPlay
            playsInline
            muted
          />
          <canvas
            ref={overlayCanvasRef}
            className="realtime-overlay-canvas"
            width={FRAME_WIDTH}
            height={FRAME_HEIGHT}
          />
          {!cameraActive || cameraStarting || cameraError ? (
            <div className="realtime-status-layer">
              {cameraStarting ? "Opening camera..." : cameraError ?? "Camera is stopped"}
            </div>
          ) : null}
        </div>

        <div className="realtime-control-bar">
          {!cameraActive ? (
            <button className="button primary" type="button" onClick={openCamera} disabled={cameraStarting}>
              <Camera size={17} />
              Start camera
            </button>
          ) : (
            <button className="button danger" type="button" onClick={stopCamera}>
              <CameraOff size={17} />
              Stop camera
            </button>
          )}
          <button
            className="button secondary"
            type="button"
            onClick={() => void switchCamera()}
            disabled={!cameraActive || cameraStarting || hasSingleCamera}
            title={hasSingleCamera ? "Only one camera is available" : "Switch camera"}
          >
            <RefreshCw size={17} />
            Switch camera
          </button>
          <button
            className="button ghost"
            type="button"
            onClick={() => setAnalysisPaused((value) => !value)}
            disabled={!cameraActive}
          >
            {analysisPaused ? <Play size={17} /> : <Pause size={17} />}
            {analysisPaused ? "Resume AI" : "Pause AI"}
          </button>
        </div>

        {cameraNotice ? <div className="alert success">{cameraNotice}</div> : null}
        {cameraError ? <div className="alert danger">{cameraError}</div> : null}
        {analysisError ? <div className="alert danger">{analysisError}</div> : null}
      </section>

      <aside className="realtime-side-panel">
        <section className="panel tight realtime-info-card">
          <div className="panel-header">
            <h3>Selected person</h3>
            <UserCheck size={19} />
          </div>
          <div className="detail-list">
            <span>Identity</span>
            <strong>{identity?.label ?? "Unknown person"}</strong>
            <span>Student code</span>
            <strong>{identity?.studentCode ?? "-"}</strong>
            <span>Class</span>
            <strong>{identity?.className ?? "-"}</strong>
            <span>Face confidence</span>
            <strong>{asPercent(identity?.confidence)}</strong>
            <span>Pose confidence</span>
            <strong>{asPercent(selectedPersonConfidence)}</strong>
          </div>
        </section>

        <section className="panel tight realtime-info-card">
          <h3>Processing</h3>
          <div className="detail-list">
            <span>Pipeline</span>
            <strong>{latestResult?.pipeline ?? "YOLOv8 Pose + InsightFace + YOLOv8 Uniform"}</strong>
            <span>Frame</span>
            <strong>{FRAME_WIDTH} x {FRAME_HEIGHT}</strong>
            <span>Latency</span>
            <strong>{latestResult?.processingTimeMs != null ? `${latestResult.processingTimeMs} ms` : "-"}</strong>
            <span>AI FPS</span>
            <strong>{fps == null ? "-" : fps.toFixed(1)}</strong>
            <span>Camera</span>
            <strong>{CAMERA_MODE_LABELS[cameraFacingMode]}</strong>
          </div>
        </section>

        <section className="panel tight realtime-info-card">
          <h3>Uniform detections</h3>
          {uniformDetections.length === 0 ? (
            <p className="muted small">No uniform objects detected.</p>
          ) : (
            <div className="realtime-detection-list">
              {uniformDetections.map((detection, index) => (
                <DetectionRow detection={detection} key={`${detection.className}-${index}`} />
              ))}
            </div>
          )}
        </section>
      </aside>
    </div>
  );
}

function DetectionRow({ detection }: { detection: RealtimeUniformDetection }) {
  return (
    <div className="realtime-detection-row">
      <span>{detection.className}</span>
      <strong>{asPercent(detection.confidence)}</strong>
    </div>
  );
}

function drawOverlay(canvas: HTMLCanvasElement | null, result: RealtimeCameraAnalysis | null) {
  if (!canvas) return;
  const context = canvas.getContext("2d");
  if (!context) return;
  context.clearRect(0, 0, canvas.width, canvas.height);
  if (!result?.success) return;

  const sourceWidth = result.frameWidth ?? FRAME_WIDTH;
  const sourceHeight = result.frameHeight ?? FRAME_HEIGHT;
  const identityLabel = result.identity?.label ?? "Unknown person";

  for (const detection of result.uniformDetections ?? []) {
    drawBox(context, detection.bbox, sourceWidth, sourceHeight, "#f97316", `${detection.className} ${asPercent(detection.confidence)}`);
  }

  if (result.face) {
    drawBox(context, result.face.bbox, sourceWidth, sourceHeight, "#38bdf8", `Face ${asPercent(result.face.confidence)}`);
  }

  if (result.selectedPerson) {
    drawSkeleton(context, result.selectedPerson.keypoints ?? [], result.selectedPerson.skeleton ?? [], sourceWidth, sourceHeight);
    drawKeypoints(context, result.selectedPerson.keypoints ?? [], sourceWidth, sourceHeight);
    drawBox(
      context,
      result.selectedPerson.bbox,
      sourceWidth,
      sourceHeight,
      result.identity?.matched ? "#22c55e" : "#facc15",
      identityLabel,
    );
  } else if (identityLabel) {
    drawLabel(context, identityLabel, 18, 28, "#facc15");
  }
}

function clearOverlay(canvas: HTMLCanvasElement | null) {
  const context = canvas?.getContext("2d");
  if (canvas && context) {
    context.clearRect(0, 0, canvas.width, canvas.height);
  }
}

function drawBox(
  context: CanvasRenderingContext2D,
  bbox: number[] | null | undefined,
  sourceWidth: number,
  sourceHeight: number,
  color: string,
  label: string,
) {
  const box = mapBbox(bbox, sourceWidth, sourceHeight);
  if (!box) return;
  const [x1, y1, x2, y2] = box;
  context.save();
  context.strokeStyle = color;
  context.lineWidth = 3;
  context.strokeRect(x1, y1, x2 - x1, y2 - y1);
  drawLabel(context, label, x1, Math.max(24, y1 - 8), color);
  context.restore();
}

function drawSkeleton(
  context: CanvasRenderingContext2D,
  keypoints: RealtimeCameraKeypoint[],
  skeleton: Array<{ from: string; to: string }>,
  sourceWidth: number,
  sourceHeight: number,
) {
  const byName = new Map(keypoints.map((keypoint) => [keypoint.name, keypoint]));
  context.save();
  context.strokeStyle = "#22d3ee";
  context.lineWidth = 3;
  context.lineCap = "round";
  for (const link of skeleton) {
    const from = byName.get(link.from);
    const to = byName.get(link.to);
    if (!from || !to || !isVisibleKeypoint(from) || !isVisibleKeypoint(to)) continue;
    context.beginPath();
    context.moveTo(mapCoordinate(from.x, sourceWidth, FRAME_WIDTH), mapCoordinate(from.y, sourceHeight, FRAME_HEIGHT));
    context.lineTo(mapCoordinate(to.x, sourceWidth, FRAME_WIDTH), mapCoordinate(to.y, sourceHeight, FRAME_HEIGHT));
    context.stroke();
  }
  context.restore();
}

function drawKeypoints(
  context: CanvasRenderingContext2D,
  keypoints: RealtimeCameraKeypoint[],
  sourceWidth: number,
  sourceHeight: number,
) {
  context.save();
  context.fillStyle = "#fde047";
  context.strokeStyle = "#111827";
  context.lineWidth = 1.5;
  for (const keypoint of keypoints) {
    if (!isVisibleKeypoint(keypoint)) continue;
    const x = mapCoordinate(keypoint.x, sourceWidth, FRAME_WIDTH);
    const y = mapCoordinate(keypoint.y, sourceHeight, FRAME_HEIGHT);
    context.beginPath();
    context.arc(x, y, 4.5, 0, Math.PI * 2);
    context.fill();
    context.stroke();
  }
  context.restore();
}

function drawLabel(context: CanvasRenderingContext2D, label: string, x: number, y: number, color: string) {
  const text = label || "Unknown person";
  context.save();
  context.font = "700 18px system-ui, sans-serif";
  const metrics = context.measureText(text);
  const width = Math.min(context.canvas.width - 12, metrics.width + 18);
  const labelX = Math.max(6, Math.min(x, context.canvas.width - width - 6));
  const labelY = Math.max(6, y - 24);
  context.fillStyle = "rgba(15, 23, 42, 0.82)";
  context.fillRect(labelX, labelY, width, 26);
  context.fillStyle = color;
  context.fillRect(labelX, labelY + 24, width, 3);
  context.fillStyle = "#f8fbfd";
  context.fillText(text, labelX + 9, labelY + 18, width - 18);
  context.restore();
}

function mapBbox(bbox: number[] | null | undefined, sourceWidth: number, sourceHeight: number) {
  if (!bbox || bbox.length < 4) return null;
  const [x1, y1, x2, y2] = bbox;
  return [
    mapCoordinate(x1, sourceWidth, FRAME_WIDTH),
    mapCoordinate(y1, sourceHeight, FRAME_HEIGHT),
    mapCoordinate(x2, sourceWidth, FRAME_WIDTH),
    mapCoordinate(y2, sourceHeight, FRAME_HEIGHT),
  ];
}

function mapCoordinate(value: number, sourceSize: number, targetSize: number) {
  if (value >= 0 && value <= 1.5) {
    return value * targetSize;
  }
  return (value / Math.max(1, sourceSize)) * targetSize;
}

function isVisibleKeypoint(keypoint?: RealtimeCameraKeypoint | null) {
  return Boolean(keypoint && keypoint.confidence !== 0 && (keypoint.confidence == null || keypoint.confidence >= MIN_KEYPOINT_CONFIDENCE));
}

function drawVideoCover(
  video: HTMLVideoElement,
  context: CanvasRenderingContext2D,
  width: number,
  height: number,
  mirror: boolean,
) {
  const sourceWidth = video.videoWidth;
  const sourceHeight = video.videoHeight;
  const targetAspect = width / height;
  const sourceAspect = sourceWidth / sourceHeight;
  let sx = 0;
  let sy = 0;
  let sw = sourceWidth;
  let sh = sourceHeight;

  if (sourceAspect > targetAspect) {
    sw = sourceHeight * targetAspect;
    sx = (sourceWidth - sw) / 2;
  } else {
    sh = sourceWidth / targetAspect;
    sy = (sourceHeight - sh) / 2;
  }

  context.save();
  context.clearRect(0, 0, width, height);
  if (mirror) {
    context.translate(width, 0);
    context.scale(-1, 1);
  }
  context.drawImage(video, sx, sy, sw, sh, 0, 0, width, height);
  context.restore();
}

function canvasToBlob(canvas: HTMLCanvasElement, type: string, quality: number) {
  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (!blob) {
          reject(new Error("Cannot encode camera frame."));
          return;
        }
        resolve(blob);
      },
      type,
      quality,
    );
  });
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

function clearAnalysisTimer(timerRef: MutableRefObject<number | null>) {
  if (timerRef.current != null) {
    window.clearTimeout(timerRef.current);
    timerRef.current = null;
  }
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
      return "Camera permission was denied.";
    }
    if (error.name === "NotFoundError" || error.name === "DevicesNotFoundError") {
      return "No camera was found on this device.";
    }
    if (error.name === "NotReadableError" || error.name === "TrackStartError") {
      return "The camera is being used by another application.";
    }
    if (error.name === "OverconstrainedError" || error.name === "ConstraintNotSatisfiedError") {
      return "The selected camera is not available.";
    }
  }
  return "Cannot open camera. Check browser permissions and try again.";
}
