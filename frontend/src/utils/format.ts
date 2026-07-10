import type { ComplianceStatus, EvaluationMethod } from "../types";

export const UNIFORM_CLASS_LABELS: Record<string, string> = {
  ao_so_mi_trang: "Áo sơ mi trắng",
  ao_doan_thanh_nien: "Áo Đoàn Thanh niên",
  quan_tay_dai_den: "Quần tây dài đen",
  khan_quang_do: "Khăn quàng đỏ",
  quan_short_tay_den: "Quần short đen",
  quan_dai_trang: "Quần dài trắng",
};

export const COMPONENT_LABELS: Record<string, string> = {
  ...UNIFORM_CLASS_LABELS,
  ao_so_mi_trang_or_ao_doan_thanh_nien: "Áo sơ mi trắng hoặc Áo Đoàn Thanh niên",
  quan_tay_dai_den_or_quan_short_tay_den_or_quan_dai_trang:
    "Quần tây dài đen, quần short đen hoặc quần dài trắng",
};

export const VIOLATION_LABELS: Record<string, string> = {
  MISSING_WHITE_SHIRT: "Thiếu áo sơ mi trắng",
  MISSING_YOUTH_UNION_SHIRT: "Thiếu áo Đoàn Thanh niên",
  MISSING_BLACK_TROUSERS: "Thiếu quần đúng quy định",
  MISSING_RED_SCARF: "Thiếu khăn quàng đỏ",
  SHIRT_NOT_TUCKED: "Chưa sơ vin",
  WRINKLED_CLOTHES: "Đồng phục bị nhăn",
  DIRTY_CLOTHES: "Đồng phục bị bẩn",
  TORN_CLOTHES: "Đồng phục bị rách",
  NON_COMPLIANT: "Chưa đạt yêu cầu đồng phục",
  NEEDS_REVIEW: "Cần kiểm tra lại",
};

export const METHOD_LABELS: Record<string, string> = {
  METHOD_1_GROUNDING_DINO_SCHP_FLORENCE: "Grounding DINO V2 + SCHP + Florence-2",
  METHOD_2_YOLOV8_SCHP_FLORENCE: "YOLOv8 V2 + SCHP + Florence-2",
  METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO: "Pose + InsightFace + Grounding DINO",
  METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM: "Pose + InsightFace + YOLOv8 đồng phục",
  GROUNDING_DINO_V2: "Grounding DINO V2 + SCHP + Florence-2",
  YOLOV8_V2: "YOLOv8 V2 + SCHP + Florence-2",
  LIGHTWEIGHT_GROUNDING_DINO: "Pose + InsightFace + Grounding DINO",
  LIGHTWEIGHT_YOLOV8_UNIFORM: "Pose + InsightFace + YOLOv8 đồng phục",
  grounding_dino_schp_florence2: "Grounding DINO V2 + SCHP + Florence-2",
  yolov8_schp_florence2: "YOLOv8 V2 + SCHP + Florence-2",
};

export const STATUS_LABELS: Record<ComplianceStatus, string> = {
  PARTIALLY_COMPLIANT: "Cần kiểm tra lại",
  COMPLIANT: "Đạt",
  NON_COMPLIANT: "Chưa đạt",
  NEEDS_REVIEW: "Cần kiểm tra lại",
};

export function methodLabel(method?: EvaluationMethod | string | null) {
  if (!method) return "Chưa có phương pháp";
  return METHOD_LABELS[method] ?? method;
}

export function componentLabel(value?: string | null) {
  if (!value) return "Không rõ";
  return COMPONENT_LABELS[value] ?? value;
}

export function violationLabel(value?: string | null) {
  if (!value) return "Không rõ";
  return VIOLATION_LABELS[value] ?? value.replace(/_/g, " ").toLowerCase();
}

export function formatDateTime(value?: string | null) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("vi-VN", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(value));
}

export function formatDate(value?: string | null) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium" }).format(new Date(value));
}

export function asPercent(value?: number | null) {
  if (value == null || Number.isNaN(value)) return "-";
  const normalized = value > 1 ? value : value * 100;
  return `${Math.round(normalized)}%`;
}

export function numberValue(value: unknown, fallback = 0) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : fallback;
}
