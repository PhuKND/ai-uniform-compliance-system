import { api, unwrap, withPageParams } from "./http";
import type { ComplianceStatus, EvaluationHistory, EvaluationMethod, Page } from "../types";

export interface HistoryFilters {
  studentCode?: string;
  studentId?: number | string;
  studentName?: string;
  className?: string;
  method?: EvaluationMethod | "";
  status?: ComplianceStatus | "";
  fromDate?: string;
  toDate?: string;
}

export function searchHistory(filters: HistoryFilters, page = 0, size = 20) {
  const params: Record<string, unknown> = { ...filters };
  if (filters.fromDate) params.fromDate = new Date(filters.fromDate).toISOString();
  if (filters.toDate) params.toDate = new Date(filters.toDate).toISOString();
  return unwrap<Page<EvaluationHistory>>(
    api.get("/api/evaluation-history", { params: withPageParams(page, size, params) }),
  );
}

export function getHistoryDetail(id: number) {
  return unwrap<EvaluationHistory>(api.get(`/api/evaluation-history/${id}`));
}

export function getMyHistory(page = 0, size = 5) {
  return unwrap<Page<EvaluationHistory>>(
    api.get("/api/evaluation-history/me", { params: withPageParams(page, size) }),
  );
}

export function getMyHistoryDetail(id: number) {
  return unwrap<EvaluationHistory>(api.get(`/api/evaluation-history/me/${id}`));
}
