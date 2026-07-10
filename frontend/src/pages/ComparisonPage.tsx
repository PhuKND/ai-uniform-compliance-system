import { IconDeviceFloppy as Save, IconUserCheck as UserCheck } from "@tabler/icons-react";
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { chooseOfficial, getCompareStatus } from "../api/evaluations";
import { AuthenticatedImage } from "../components/AuthenticatedImage";
import { MethodCard } from "../components/MethodCard";
import { StatusBadge } from "../components/StatusBadge";
import type { EvaluationCompareResponse, EvaluationHistory, MethodProcessingStatus, MethodResult } from "../types";
import { formatDateTime } from "../utils/format";
import { finalScore } from "../utils/result";
import { readLatestComparison, saveLatestComparison } from "./UploadPage";

const METHOD_1_KEY = "GROUNDING_DINO_V2";
const METHOD_2_KEY = "YOLOV8_V2";
const LIGHTWEIGHT_METHOD_1_KEY = "LIGHTWEIGHT_GROUNDING_DINO";
const LIGHTWEIGHT_METHOD_2_KEY = "LIGHTWEIGHT_YOLOV8_UNIFORM";
const POLL_INTERVAL_MS = 1500;

type MethodMap = Record<string, MethodResult>;

export function ComparisonPage() {
  const location = useLocation();
  const initialResult = useMemo(() => {
    const stateResult = (location.state as { result?: EvaluationCompareResponse } | null)?.result;
    if (stateResult) {
      saveLatestComparison(stateResult);
      return stateResult;
    }
    return readLatestComparison();
  }, [location.state]);

  const initialMethods = useMemo(() => buildMethodMap(initialResult), [initialResult]);
  const [comparison, setComparison] = useState<EvaluationCompareResponse | null>(initialResult);
  const [methodsByKey, setMethodsByKey] = useState<MethodMap>(initialMethods);
  const [selectedMethodKey, setSelectedMethodKey] = useState<string | null>(() => firstCompletedKey(initialMethods));
  const [studentCode, setStudentCode] = useState(
    initialResult?.recognizedStudentCode ?? initialResult?.requestedStudentCode ?? initialResult?.student?.studentCode ?? "",
  );
  const [adminNote, setAdminNote] = useState("");
  const [deductionInputs, setDeductionInputs] = useState<Record<string, string>>({});
  const [savedHistory, setSavedHistory] = useState<EvaluationHistory | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pollingError, setPollingError] = useState<string | null>(null);

  const applyComparisonUpdate = useCallback((next: EvaluationCompareResponse) => {
    setPollingError(null);
    setMethodsByKey((previousMethods) => {
      const mergedMethods = mergeMethodMap(previousMethods, next);
      setComparison((previousComparison) => {
        const mergedComparison = mergeComparison(previousComparison, next, mergedMethods);
        saveLatestComparison(mergedComparison);
        return mergedComparison;
      });
      setSelectedMethodKey((previousSelected) => {
        if (previousSelected && isCompletedMethod(mergedMethods[previousSelected])) {
          return previousSelected;
        }
        return firstCompletedKey(mergedMethods) ?? previousSelected;
      });
      return mergedMethods;
    });

    const nextStudentCode = next.recognizedStudentCode ?? next.requestedStudentCode ?? next.student?.studentCode;
    if (nextStudentCode) {
      setStudentCode((current) => current || nextStudentCode);
    }
  }, []);

  useEffect(() => {
    if (initialResult) {
      applyComparisonUpdate(initialResult);
    }
  }, [applyComparisonUpdate, initialResult]);

  const jobId = comparison?.jobId ?? comparison?.runId;
  const jobStatus = comparison?.status;
  const selectedMethod = selectedMethodKey ? methodsByKey[selectedMethodKey] : null;
  const selectedMethodCompleted = isCompletedMethod(selectedMethod);
  const selectedScore = selectedMethod ? finalScore(selectedMethod) : null;
  const selectedDeduction = selectedMethod ? automaticConductDeduction(selectedMethod) : null;
  const deductionInput = selectedMethodKey ? deductionInputs[selectedMethodKey] ?? "" : "";

  useEffect(() => {
    if (!selectedMethodKey || !selectedMethodCompleted || selectedDeduction == null) return;
    setDeductionInputs((current) =>
      Object.prototype.hasOwnProperty.call(current, selectedMethodKey)
        ? current
        : { ...current, [selectedMethodKey]: String(selectedDeduction) },
    );
  }, [selectedDeduction, selectedMethodCompleted, selectedMethodKey]);

  useEffect(() => {
    if (!jobId || isTerminalJobStatus(jobStatus)) {
      return;
    }

    const activeJobId = jobId;
    let cancelled = false;
    let timeoutId: number | undefined;

    async function poll() {
      try {
        const next = await getCompareStatus(activeJobId);
        if (cancelled) return;
        applyComparisonUpdate(next);
        if (!isTerminalJobStatus(next.status)) {
          timeoutId = window.setTimeout(poll, POLL_INTERVAL_MS);
        }
      } catch (err) {
        if (cancelled) return;
        setPollingError(err instanceof Error ? err.message : "Không thể cập nhật trạng thái AI.");
        timeoutId = window.setTimeout(poll, POLL_INTERVAL_MS * 2);
      }
    }

    timeoutId = window.setTimeout(poll, 800);
    return () => {
      cancelled = true;
      if (timeoutId) window.clearTimeout(timeoutId);
    };
  }, [applyComparisonUpdate, jobId, jobStatus]);

  if (!comparison) {
    return (
      <div className="page-content empty-state">
        <h2>Chưa có kết quả so sánh</h2>
        <p className="muted">Hãy tải ảnh mới để chạy đánh giá AI trước khi chọn kết quả chính thức.</p>
        <Link className="button primary" to="/upload">
          Tải ảnh đánh giá
        </Link>
      </div>
    );
  }

  const visibleMethods = visibleMethodList(methodsByKey, comparison);
  const singleMethodResult = visibleMethods.length === 1;
  const runId = comparison.runId;

  async function submitOfficial(event: FormEvent) {
    event.preventDefault();
    if (!selectedMethod || !selectedMethodCompleted) {
      setError("Vui lòng chọn một phương pháp đã hoàn tất.");
      return;
    }
    if (!/^\d+$/.test(deductionInput.trim())) {
      setError("Điểm trừ rèn luyện phải là số nguyên không âm.");
      return;
    }
    const deductedPoints = Number(deductionInput);
    if (!Number.isSafeInteger(deductedPoints) || deductedPoints < 0 || deductedPoints > 100) {
      setError("Điểm trừ rèn luyện phải là số nguyên từ 0 đến 100.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const history = await chooseOfficial(runId, {
        selectedMethod: selectedMethod.methodKey,
        studentCode: studentCode.trim() || undefined,
        deductedPoints,
        adminNote: adminNote.trim() || undefined,
      });
      setSavedHistory(history);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể lưu kết quả chính thức.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="page-content">
      <div className="comparison-summary">
        <div>
          <p className="eyebrow">Run #{comparison.runId}</p>
          <h2>{singleMethodResult ? "Kết quả phương pháp AI" : "So sánh hai phương pháp AI"}</h2>
          <p className="muted">Tạo lúc {formatDateTime(comparison.createdAt)} · {jobStatusLabel(comparison.status)}</p>
        </div>
        <div className="summary-student">
          <UserCheck size={20} />
          <div>
            <span>Học sinh nhận diện</span>
            <strong>{comparison.recognizedStudentCode ?? comparison.requestedStudentCode ?? "Chưa xác định"}</strong>
          </div>
        </div>
      </div>

      {pollingError ? <div className="alert danger">{pollingError}</div> : null}

      <section className="preai-grid">
        <div className="media-frame">
          <AuthenticatedImage src={comparison.originalImageUrl} alt="Ảnh trước AI" />
        </div>
        <div className="panel">
          <h3>Thông tin đối tượng</h3>
          <div className="detail-list">
            <span>Mã yêu cầu</span>
            <strong>{comparison.requestedStudentCode ?? "-"}</strong>
            <span>Mã AI nhận diện</span>
            <strong>{comparison.recognizedStudentCode ?? "-"}</strong>
            <span>Họ tên</span>
            <strong>{comparison.student?.fullName ?? "-"}</strong>
            <span>Lớp</span>
            <strong>{comparison.student?.className ?? "-"}</strong>
            <span>Tuổi hiện tại</span>
            <strong>{comparison.student?.age ?? "-"}</strong>
          </div>
        </div>
      </section>

      <section className={`method-grid ${visibleMethods.length === 1 ? "single" : ""}`}>
        {visibleMethods.map((method) => (
          <MethodCard
            key={method.methodKey}
            method={method}
            selected={selectedMethodKey === method.methodKey}
            onSelect={() => setSelectedMethodKey(method.methodKey)}
          />
        ))}
      </section>

      <section className="official-panel">
        <div>
          <h3>Lưu kết quả chính thức</h3>
          <p className="muted">Kết quả đã lưu sẽ đi vào lịch sử, lưu ảnh đã chọn và trừ điểm rèn luyện nếu có.</p>
        </div>
        <form className="official-form" onSubmit={submitOfficial}>
          <label>
            Mã học sinh áp dụng
            <input value={studentCode} onChange={(event) => setStudentCode(event.target.value)} />
          </label>
          <div className="official-preview">
            <span>Điểm tuân thủ lịch lớp</span>
            <strong>{selectedMethodCompleted ? selectedScore ?? "-" : "-"}</strong>
          </div>
          <label className="official-deduction-field">
            Điểm trừ rèn luyện tự động
            <input
              type="number"
              min={0}
              max={100}
              step={1}
              inputMode="numeric"
              value={deductionInput}
              onChange={(event) => {
                if (!selectedMethodKey) return;
                setDeductionInputs((current) => ({ ...current, [selectedMethodKey]: event.target.value }));
              }}
              disabled={!selectedMethodCompleted || Boolean(savedHistory)}
              required
            />
            <small>Giá trị do hệ thống đề xuất, quản trị viên có thể điều chỉnh trước khi lưu chính thức.</small>
          </label>
          <div className="official-preview">
            <span>Trạng thái</span>
            {selectedMethodCompleted ? <StatusBadge status={selectedMethod?.complianceStatus} /> : <strong>-</strong>}
          </div>
          <label className="full-span">
            Ghi chú quản trị
            <textarea rows={3} value={adminNote} onChange={(event) => setAdminNote(event.target.value)} />
          </label>
          {error ? <div className="alert danger full-span">{error}</div> : null}
          {savedHistory ? (
            <div className="alert success full-span">
              Đã lưu lịch sử #{savedHistory.id} với {savedHistory.deductedPoints} điểm trừ. Trạng thái:{" "}
              <StatusBadge status={savedHistory.complianceStatus} />
            </div>
          ) : null}
          <button
            className="button primary full-span"
            type="submit"
            disabled={saving || Boolean(savedHistory) || !selectedMethodCompleted}
          >
            <Save size={17} />
            {saving ? "Đang lưu..." : "Lưu chính thức"}
          </button>
        </form>
      </section>
    </div>
  );
}

function automaticConductDeduction(method: MethodResult) {
  const scheduleDeduction = method.scheduleResult?.deductedPoints ?? method.result?.backend_schedule_result?.deductedPoints;
  if (typeof scheduleDeduction === "number") return scheduleDeduction;
  const backend = method.result?.backend_final_result as Record<string, unknown> | undefined;
  const backendDeduction = backend?.automatic_conduct_deduction ?? backend?.deducted_points;
  if (typeof backendDeduction === "number") return backendDeduction;
  return null;
}

function buildMethodMap(response?: EvaluationCompareResponse | null): MethodMap {
  const map: MethodMap = {
    [METHOD_1_KEY]: processingMethod(
      "METHOD_1_GROUNDING_DINO_SCHP_FLORENCE",
      METHOD_1_KEY,
      "Grounding DINO V2 + SCHP + Florence-2",
    ),
    [METHOD_2_KEY]: processingMethod(
      "METHOD_2_YOLOV8_SCHP_FLORENCE",
      METHOD_2_KEY,
      "YOLOv8 V2 + SCHP + Florence-2",
    ),
  };
  if (!response) {
    return map;
  }
  return mergeMethodMap(map, response);
}

function processingMethod(method: MethodResult["method"], methodKey: string, methodDisplayName: string): MethodResult {
  return {
    method,
    complianceStatus: "NEEDS_REVIEW",
    processedImageId: null,
    processedImageUrl: null,
    aiProcessedImageUrl: null,
    rawResult: null,
    methodKey,
    methodDisplayName,
    processedImagePath: null,
    result: null,
    status: "processing",
    score: null,
    resultStatus: null,
    validComponents: [],
    missingComponents: [],
    excludedComponents: [],
    message: "Đang xử lý",
    note: null,
    error: null,
    completedAt: null,
  };
}

function mergeMethodMap(previous: MethodMap, response: EvaluationCompareResponse): MethodMap {
  const merged: MethodMap = { ...previous };
  for (const method of responseMethods(response)) {
    const key = canonicalMethodKey(method);
    merged[key] = mergeMethod(merged[key] ?? method, method);
  }
  return merged;
}

function responseMethods(response: EvaluationCompareResponse): MethodResult[] {
  if (response.results?.length) return response.results;
  if (response.candidates?.length) return response.candidates;
  return [response.method1, response.method2].filter((method): method is MethodResult => Boolean(method));
}

function canonicalMethodKey(method: MethodResult): string {
  const methodKey = method.methodKey?.toUpperCase();
  if (
    methodKey === METHOD_1_KEY ||
    methodKey === LIGHTWEIGHT_METHOD_1_KEY ||
    method.methodKey === "grounding_dino_schp_florence2" ||
    method.method === "METHOD_1_GROUNDING_DINO_SCHP_FLORENCE" ||
    method.method === "METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO" ||
    method.method === LIGHTWEIGHT_METHOD_1_KEY ||
    method.method === METHOD_1_KEY
  ) {
    return methodKey === LIGHTWEIGHT_METHOD_1_KEY || method.method === "METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO"
      ? LIGHTWEIGHT_METHOD_1_KEY
      : METHOD_1_KEY;
  }
  if (
    methodKey === METHOD_2_KEY ||
    methodKey === LIGHTWEIGHT_METHOD_2_KEY ||
    method.methodKey === "yolov8_schp_florence2" ||
    method.method === "METHOD_2_YOLOV8_SCHP_FLORENCE" ||
    method.method === "METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM" ||
    method.method === LIGHTWEIGHT_METHOD_2_KEY ||
    method.method === METHOD_2_KEY
  ) {
    return methodKey === LIGHTWEIGHT_METHOD_2_KEY || method.method === "METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM"
      ? LIGHTWEIGHT_METHOD_2_KEY
      : METHOD_2_KEY;
  }
  return method.methodKey;
}

function visibleMethodList(methodsByKey: MethodMap, comparison: EvaluationCompareResponse): MethodResult[] {
  const explicitKeys = responseMethods(comparison).map(canonicalMethodKey).filter(unique);
  const keys = explicitKeys.length > 0 ? explicitKeys : [METHOD_1_KEY, METHOD_2_KEY];
  return keys.map((key) => methodsByKey[key]).filter((method): method is MethodResult => Boolean(method));
}

function unique(value: string, index: number, values: string[]) {
  return values.indexOf(value) === index;
}

function mergeMethod(previous: MethodResult, next: MethodResult): MethodResult {
  const nextStatus = next.status ?? inferMethodStatus(next);
  if (isCompletedMethod(previous) && (nextStatus === "processing" || nextStatus === "pending")) {
    return previous;
  }

  return {
    ...previous,
    ...next,
    complianceStatus: next.complianceStatus ?? previous.complianceStatus,
    processedImageId: next.processedImageId ?? previous.processedImageId,
    processedImageUrl: next.processedImageUrl ?? previous.processedImageUrl,
    aiProcessedImageUrl: next.aiProcessedImageUrl ?? previous.aiProcessedImageUrl,
    rawResult: next.rawResult ?? previous.rawResult,
    processedImagePath: next.processedImagePath ?? previous.processedImagePath,
    result: next.result ?? previous.result,
    status: nextStatus ?? previous.status,
    score: next.score ?? previous.score,
    resultStatus: next.resultStatus ?? previous.resultStatus,
    validComponents: next.validComponents ?? previous.validComponents,
    missingComponents: next.missingComponents ?? previous.missingComponents,
    excludedComponents: next.excludedComponents ?? previous.excludedComponents,
    message: next.message ?? previous.message,
    note: next.note ?? previous.note,
    error: next.error ?? previous.error,
    completedAt: next.completedAt ?? previous.completedAt,
  };
}

function mergeComparison(
  previous: EvaluationCompareResponse | null,
  next: EvaluationCompareResponse,
  methodsByKey: MethodMap,
): EvaluationCompareResponse {
  const base = previous ?? next;
  const method1 = methodsByKey[LIGHTWEIGHT_METHOD_1_KEY] ?? methodsByKey[METHOD_1_KEY];
  const method2 = methodsByKey[LIGHTWEIGHT_METHOD_2_KEY] ?? methodsByKey[METHOD_2_KEY];
  const explicitKeys = responseMethods(next).map(canonicalMethodKey).filter(unique);
  const methods =
    explicitKeys.length > 0
      ? explicitKeys.map((key) => methodsByKey[key]).filter((method): method is MethodResult => Boolean(method))
      : [method1, method2].filter((method): method is MethodResult => Boolean(method));

  return {
    ...base,
    ...next,
    runId: next.runId ?? base.runId,
    requestedStudentCode: next.requestedStudentCode ?? base.requestedStudentCode,
    recognizedStudentCode: next.recognizedStudentCode ?? base.recognizedStudentCode,
    originalImageId: next.originalImageId ?? base.originalImageId,
    createdAt: next.createdAt ?? base.createdAt,
    uniformAiEvaluationId: next.uniformAiEvaluationId ?? base.uniformAiEvaluationId,
    preAiImagePath: next.preAiImagePath ?? base.preAiImagePath,
    preAiImageUrl: next.preAiImageUrl ?? base.preAiImageUrl,
    originalImageUrl: next.originalImageUrl ?? base.originalImageUrl,
    student: next.student ?? base.student,
    method1,
    method2,
    candidates: methods,
    results: methods,
    jobId: next.jobId ?? base.jobId ?? next.runId,
    status: next.status ?? base.status,
    updatedAt: next.updatedAt ?? base.updatedAt,
  };
}

function inferMethodStatus(method?: MethodResult | null): MethodProcessingStatus {
  if (!method) return "processing";
  if (method.status) return method.status;
  return method.rawResult || method.result ? "completed" : "processing";
}

function isCompletedMethod(method?: MethodResult | null) {
  return inferMethodStatus(method) === "completed";
}

function firstCompletedKey(methodsByKey: MethodMap) {
  return [LIGHTWEIGHT_METHOD_1_KEY, LIGHTWEIGHT_METHOD_2_KEY, METHOD_1_KEY, METHOD_2_KEY].find((key) =>
    isCompletedMethod(methodsByKey[key]),
  ) ?? null;
}

function isTerminalJobStatus(status?: string | null) {
  return status === "completed" || status === "failed";
}

function jobStatusLabel(status?: string | null) {
  switch (status) {
    case "completed":
      return "Hoàn tất";
    case "partial":
      return "Đang cập nhật từng phương pháp";
    case "failed":
      return "Thất bại";
    case "processing":
    default:
      return "Đang xử lý";
  }
}
