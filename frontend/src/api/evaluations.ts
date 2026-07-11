import { api, unwrap } from "./http";
import type { ChooseOfficialRequest, EvaluationCompareResponse, EvaluationHistory } from "../types";

export type AdvancedEvaluationMethod = "YOLOV8_V2" | "GROUNDING_DINO_V2";

export function compareEvaluation(image: File, studentCode?: string) {
  const formData = new FormData();
  formData.append("image", image);
  if (studentCode?.trim()) {
    formData.append("studentCode", studentCode.trim());
  }
  return unwrap<EvaluationCompareResponse>(
    api.post("/api/evaluations/compare", formData, {
      headers: { "Content-Type": "multipart/form-data" },
    }),
  );
}

export function startCompareEvaluation(image: File, studentCode?: string) {
  const formData = new FormData();
  formData.append("image", image);
  if (studentCode?.trim()) {
    formData.append("studentCode", studentCode.trim());
  }
  return unwrap<EvaluationCompareResponse>(
    api.post("/api/evaluations/compare/start", formData, {
      headers: { "Content-Type": "multipart/form-data" },
    }),
  );
}

export function runAdvancedEvaluation(image: File, method: AdvancedEvaluationMethod, studentCode?: string) {
  const formData = new FormData();
  formData.append("image", image);
  if (studentCode?.trim()) {
    formData.append("studentCode", studentCode.trim());
  }
  const endpoint =
    method === "GROUNDING_DINO_V2"
      ? "/api/admin/evaluations/grounding-dino-v2"
      : "/api/admin/evaluations/yolov8-v2";
  return unwrap<EvaluationCompareResponse>(
    api.post(endpoint, formData, {
      headers: { "Content-Type": "multipart/form-data" },
    }),
  );
}

export function runLightweightEvaluation(image: File, studentCode?: string) {
  const formData = new FormData();
  formData.append("image", image);
  if (studentCode?.trim()) {
    formData.append("studentCode", studentCode.trim());
  }
  return unwrap<EvaluationCompareResponse>(
    api.post("/api/admin/evaluations/lightweight", formData, {
      headers: { "Content-Type": "multipart/form-data" },
    }),
  );
}

export function getCompareStatus(jobId: number) {
  return unwrap<EvaluationCompareResponse>(api.get(`/api/evaluations/compare/status/${jobId}`));
}

export function chooseOfficial(runId: number, request: ChooseOfficialRequest) {
  return unwrap<EvaluationHistory>(api.post(`/api/evaluations/${runId}/choose-official`, request));
}
