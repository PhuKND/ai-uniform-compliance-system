import type { CandidateResult, ComponentEvidence, MethodResult } from "../types";

export function finalScore(method: MethodResult) {
  const scheduleScore = method.scheduleResult?.score ?? method.result?.backend_schedule_result?.score;
  if (typeof scheduleScore === "number") return scheduleScore;
  const backend = method.result?.backend_final_result as Record<string, unknown> | undefined;
  const backendScore = backend?.canonical_score ?? backend?.finalScore ?? backend?.final_score;
  if (typeof backendScore === "number") return backendScore;
  return null;
}

export function finalComment(method: MethodResult) {
  const backend = method.result?.backend_final_result as Record<string, unknown> | undefined;
  const backendComment = backend?.finalComment ?? backend?.final_comment;
  return (
    method.message ||
    (typeof backendComment === "string" ? backendComment : null) ||
    method.result?.final_summary?.vietnamese_comment ||
    "Chưa có nhận xét từ hệ thống."
  );
}

export function acceptedComponents(result?: CandidateResult | null): ComponentEvidence[] {
  return Array.isArray(result?.accepted_components) ? result.accepted_components : [];
}

export function missingComponents(result?: CandidateResult | null): string[] {
  const backend = result?.backend_final_result as Record<string, unknown> | undefined;
  const backendMissing = backend?.missingComponents ?? backend?.missing_components;
  if (Array.isArray(backendMissing)) return backendMissing;
  return Array.isArray(result?.missing_components) ? result.missing_components : [];
}

export function rejectedComponents(result?: CandidateResult | null): ComponentEvidence[] {
  return Array.isArray(result?.rejected_components) ? result.rejected_components : [];
}

export function removedDuplicateComponents(result?: CandidateResult | null): ComponentEvidence[] {
  if (Array.isArray(result?.removed_duplicate_components)) return result.removed_duplicate_components;
  if (Array.isArray(result?.removed_duplicate_detections)) return result.removed_duplicate_detections;
  return [];
}
