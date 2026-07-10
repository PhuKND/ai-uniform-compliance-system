import { api, unwrap } from "./http";
import type { AdminStatistics } from "../types";

export function getAdminStatistics() {
  return unwrap<AdminStatistics>(api.get("/api/statistics/admin"));
}
