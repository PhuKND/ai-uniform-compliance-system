import { api, unwrap, withPageParams } from "./http";
import type { CorrectionRequest, Page, ResolveCorrectionRequestInput } from "../types";

export function listCorrectionRequests(page = 0, size = 20) {
  return unwrap<Page<CorrectionRequest>>(
    api.get("/api/correction-requests", { params: withPageParams(page, size) }),
  );
}

export function listMyCorrectionRequests(page = 0, size = 20) {
  return unwrap<Page<CorrectionRequest>>(
    api.get("/api/correction-requests/me", { params: withPageParams(page, size) }),
  );
}

export function createCorrectionRequest(
  evaluationHistoryId: number,
  requestedDeduction: number,
  reason: string,
  evidenceNote?: string,
  evidenceImage?: File | null,
) {
  const formData = new FormData();
  formData.append("evaluationHistoryId", String(evaluationHistoryId));
  formData.append("requestedDeduction", String(requestedDeduction));
  formData.append("reason", reason);
  if (evidenceNote?.trim()) {
    formData.append("evidenceNote", evidenceNote.trim());
  }
  if (evidenceImage) {
    formData.append("evidenceImage", evidenceImage);
  }

  return unwrap<CorrectionRequest>(
    api.post("/api/correction-requests", formData, {
      headers: { "Content-Type": "multipart/form-data" },
    }),
  );
}

export function cancelCorrectionRequest(id: number) {
  return unwrap<CorrectionRequest>(api.post(`/api/correction-requests/${id}/cancel`));
}

export function approveCorrectionRequest(id: number, input: ResolveCorrectionRequestInput) {
  return unwrap<CorrectionRequest>(api.post(`/api/correction-requests/${id}/approve`, normalizeResolveInput(input)));
}

export function rejectCorrectionRequest(id: number, input: ResolveCorrectionRequestInput) {
  return unwrap<CorrectionRequest>(api.post(`/api/correction-requests/${id}/reject`, normalizeResolveInput(input)));
}

function normalizeResolveInput(input: ResolveCorrectionRequestInput) {
  return {
    adminResponseNote: clean(input.adminResponseNote),
    updatedViolationSummary: clean(input.updatedViolationSummary),
  };
}

function clean(value: unknown) {
  return typeof value === "string" && value.trim() === "" ? null : value;
}
