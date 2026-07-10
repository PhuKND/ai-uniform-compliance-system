import { api, unwrap } from "./http";
import type { RealtimeCameraAnalysis } from "../types";

export function analyzeRealtimeCameraFrame(image: Blob, frameWidth: number, frameHeight: number) {
  const formData = new FormData();
  const file = new File([image], `realtime-frame-${Date.now()}.jpg`, {
    type: image.type || "image/jpeg",
  });
  formData.append("image", file);
  formData.append("frameWidth", String(frameWidth));
  formData.append("frameHeight", String(frameHeight));

  return unwrap<RealtimeCameraAnalysis>(
    api.post("/api/admin/realtime-camera/analyze-frame", formData, {
      headers: { "Content-Type": "multipart/form-data" },
      timeout: 15000,
    }),
  );
}
