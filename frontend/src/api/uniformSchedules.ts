import { api, unwrap } from "./http";
import type {
  UniformClassOption,
  UniformRequirementScheduleResponse,
  UniformRequirementScheduleUpdateInput,
  StudentUniformScheduleResponse,
} from "../types";

export function listUniformClasses() {
  return unwrap<UniformClassOption[]>(api.get("/api/students/classes"));
}

export function getUniformRequirementSchedule(classId: string) {
  return unwrap<UniformRequirementScheduleResponse>(
    api.get(`/api/admin/uniform-requirement-schedules/${encodeURIComponent(classId)}`),
  );
}

export function updateUniformRequirementSchedule(classId: string, input: UniformRequirementScheduleUpdateInput) {
  return unwrap<UniformRequirementScheduleResponse>(
    api.put(`/api/admin/uniform-requirement-schedules/${encodeURIComponent(classId)}`, input),
  );
}

export function getMyUniformSchedule() {
  return unwrap<StudentUniformScheduleResponse>(api.get("/api/student/uniform-schedule"));
}
