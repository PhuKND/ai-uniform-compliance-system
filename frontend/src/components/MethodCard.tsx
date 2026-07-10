import {
  IconAlertTriangle as AlertTriangle,
  IconCircleCheck as CheckCircle2,
  IconLoader2 as LoaderCircle,
} from "@tabler/icons-react";
import { ComponentList } from "./ComponentList";
import { ProcessedImagePreview } from "./ProcessedImagePreview";
import { StatusBadge } from "./StatusBadge";
import type { MethodProcessingStatus, MethodResult, ScheduleComplianceResult } from "../types";
import { componentLabel, methodLabel } from "../utils/format";
import {
  acceptedComponents,
  finalComment,
  finalScore,
  missingComponents,
  rejectedComponents,
  removedDuplicateComponents,
} from "../utils/result";

interface MethodCardProps {
  method: MethodResult;
  selected?: boolean;
  onSelect?: () => void;
}

export function MethodCard({ method, selected, onSelect }: MethodCardProps) {
  const status = methodStatus(method);
  const isProcessing = status === "processing" || status === "pending";
  const isFailed = status === "failed";
  const isCompleted = status === "completed";
  const score = finalScore(method);
  const imageSrc = bestImageUrl(method);
  const comment = method.error || method.message || finalComment(method);
  const validComponents = method.validComponents ?? acceptedComponents(method.result);
  const missing = method.missingComponents ?? missingComponents(method.result);
  const excluded = method.excludedComponents ?? rejectedComponents(method.result);
  const duplicates = removedDuplicateComponents(method.result);
  const detectorStats = detectionStats(method);
  const schedule = scheduleResult(method);

  return (
    <article className={`method-card ${selected ? "selected" : ""}`}>
      <div className="method-card-header">
        <div>
          <p className="eyebrow">{method.methodKey}</p>
          <h3>{method.methodDisplayName || methodLabel(method.method)}</h3>
        </div>
        <MethodStateBadge method={method} status={status} />
      </div>

      <div className="media-frame processed-media-frame">
        {isProcessing ? (
          <div className="method-placeholder">
            <LoaderCircle className="spin" size={26} />
            <span>Đang xử lý ảnh...</span>
          </div>
        ) : isFailed && !imageSrc ? (
          <div className="method-placeholder danger">
            <AlertTriangle size={26} />
            <span>{method.error || "Phương pháp này thất bại."}</span>
          </div>
        ) : (
          <ProcessedImagePreview src={imageSrc} alt={method.methodDisplayName} />
        )}
      </div>

      <div className="score-row">
        <span>Điểm tuân thủ lịch lớp</span>
        <strong>{isCompleted ? score ?? "-" : "-"}</strong>
      </div>
      {schedule ? <ScheduleSummary schedule={schedule} /> : null}
      {detectorStats ? (
        <div className="detector-stats" aria-label="Thông tin detector">
          <span>Raw: {detectorStats.raw}</span>
          <span>Pose: {detectorStats.poseAccepted}</span>
          <span>Final: {detectorStats.finalUnique}</span>
          <span>Trùng: {detectorStats.duplicates}</span>
        </div>
      ) : null}
      <p className="method-comment">{comment}</p>

      <ComponentList
        title="Thành phần hợp lệ"
        items={validComponents}
        empty="Chưa ghi nhận thành phần hợp lệ."
      />
      <ComponentList
        title="Thiếu"
        items={missing}
        empty="Không thiếu thành phần bắt buộc."
        tone="warning"
      />
      <ComponentList
        title="Bị loại khỏi người được chọn"
        items={excluded}
        empty="Không có thành phần bị loại."
        tone="danger"
      />
      <ComponentList
        title="Trùng lớp đã loại"
        items={duplicates}
        empty="Không có phát hiện trùng lớp bị loại."
        tone="warning"
      />

      {onSelect ? (
        <button
          className={`button ${selected ? "primary" : "secondary"} full-width`}
          type="button"
          disabled={!isCompleted}
          onClick={isCompleted ? onSelect : undefined}
        >
          <CheckCircle2 size={17} />
          {selected ? "Đang chọn" : isCompleted ? "Chọn làm kết quả chính thức" : "Chưa thể chọn"}
        </button>
      ) : null}
    </article>
  );
}

function MethodStateBadge({ method, status }: { method: MethodResult; status: MethodProcessingStatus }) {
  if (status === "completed") {
    return <StatusBadge status={method.complianceStatus} />;
  }
  if (status === "failed") {
    return (
      <span className="badge danger">
        <AlertTriangle size={14} />
        Lỗi
      </span>
    );
  }
  return (
    <span className="badge neutral">
      <LoaderCircle className="spin" size={14} />
      Đang xử lý
    </span>
  );
}

function ScheduleSummary({ schedule }: { schedule: ScheduleComplianceResult }) {
  if (!schedule.configured || !schedule.applicable) {
    return (
      <div className="schedule-result-summary muted-summary">
        <div>
          <span>Lịch lớp</span>
          <strong>-</strong>
        </div>
        <p>{scheduleReasonLabel(schedule.reason)}</p>
      </div>
    );
  }

  const missing = schedule.missingComponents ?? [];
  return (
    <div className={`schedule-result-summary ${missing.length ? "warning-summary" : "success-summary"}`}>
      <div>
        <span>Lịch lớp</span>
        <strong>{schedule.score ?? "-"}</strong>
      </div>
      <p>
        {missing.length
          ? `Thiếu theo lịch: ${missing.map((key) => componentLabel(key)).join(", ")}`
          : "Đủ thành phần theo lịch lớp."}
        {schedule.deductedPoints != null ? ` Điểm trừ rèn luyện tự động: ${schedule.deductedPoints}.` : ""}
      </p>
    </div>
  );
}

function methodStatus(method: MethodResult): MethodProcessingStatus {
  if (method.status) return method.status;
  return method.rawResult || method.result ? "completed" : "processing";
}

function detectionStats(method: MethodResult) {
  const trace = method.result?.detector_trace;
  const raw = method.rawDetectionCount ?? trace?.raw_detections?.length;
  const poseAccepted = method.poseAcceptedDetectionCount ?? trace?.pose_accepted_detections?.length;
  const finalUnique = method.finalUniqueDetectionCount ?? trace?.final_unique_per_class_detections?.length;
  const duplicates = method.duplicateRemovedCount ?? trace?.removed_duplicate_detections?.length;
  if ([raw, poseAccepted, finalUnique, duplicates].every((value) => value == null)) {
    return null;
  }
  return {
    raw: raw ?? 0,
    poseAccepted: poseAccepted ?? 0,
    finalUnique: finalUnique ?? 0,
    duplicates: duplicates ?? 0,
  };
}

function scheduleResult(method: MethodResult): ScheduleComplianceResult | null {
  if (method.scheduleResult) return method.scheduleResult;
  if (method.result?.backend_schedule_result) return method.result.backend_schedule_result;
  const raw = asRecord(method.rawResult);
  const rawResult = asRecord(raw?.result);
  return (rawResult?.backend_schedule_result as ScheduleComplianceResult | undefined) ?? null;
}

function scheduleReasonLabel(reason?: string | null) {
  switch (reason) {
    case "student_not_resolved":
      return "Chưa xác định học sinh.";
    case "student_class_not_resolved":
      return "Chưa xác định lớp học sinh.";
    case "weekday_schedule_not_configured":
      return "Chưa cấu hình lịch cho ngày này.";
    case "identity_needs_review":
      return "Danh tính cần kiểm tra lại.";
    default:
      return "Chưa áp dụng lịch lớp.";
  }
}

function bestImageUrl(method: MethodResult) {
  const raw = asRecord(method.rawResult);
  const result = asRecord(method.result);
  const rawResult = asRecord(raw?.result);

  return firstText(
    method.processedImageUrl,
    method.processedImageId ? `/api/images/${method.processedImageId}` : null,
    method.aiProcessedImageUrl,
    textField(raw, "processedImageUrl"),
    textField(raw, "processed_image_url"),
    textField(raw, "annotatedImageUrl"),
    textField(raw, "annotated_image_url"),
    textField(raw, "imageUrl"),
    textField(raw, "image_url"),
    textField(raw, "finalAnnotatedImageUrl"),
    textField(raw, "final_annotated_image_url"),
    textField(result, "processedImageUrl"),
    textField(result, "processed_image_url"),
    textField(result, "annotatedImageUrl"),
    textField(result, "annotated_image_url"),
    textField(result, "imageUrl"),
    textField(result, "image_url"),
    textField(rawResult, "processedImageUrl"),
    textField(rawResult, "processed_image_url"),
    textField(rawResult, "annotatedImageUrl"),
    textField(rawResult, "annotated_image_url"),
    textField(rawResult, "imageUrl"),
    textField(rawResult, "image_url"),
  );
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function textField(record: Record<string, unknown> | null, key: string) {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value : null;
}

function firstText(...values: Array<string | null | undefined>) {
  return values.find((value) => value != null && value.trim() !== "") ?? null;
}
