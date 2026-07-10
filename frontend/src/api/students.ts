import { api, unwrap, withPageParams } from "./http";
import type { FaceDataStatus, Page, Student, StudentAccountInput, StudentAccountResponse, StudentInput } from "../types";

export interface StudentFilters {
  keyword?: string;
  className?: string;
  active?: string;
}

export function listStudents(filters: StudentFilters, page = 0, size = 20) {
  return unwrap<Page<Student>>(
    api.get("/api/students", { params: withPageParams(page, size, { ...filters }) }),
  );
}

export function createStudent(input: StudentInput) {
  return unwrap<Student>(api.post("/api/students", normalizeStudentInput(input, false)));
}

export function updateStudent(studentCode: string, input: StudentInput) {
  return unwrap<Student>(api.put(`/api/students/${encodeURIComponent(studentCode)}`, normalizeStudentInput(input, true)));
}

export function deactivateStudent(studentCode: string) {
  return unwrap<void>(api.delete(`/api/students/${encodeURIComponent(studentCode)}`));
}

export function createStudentAccount(studentCode: string, input: StudentAccountInput) {
  return unwrap<StudentAccountResponse>(
    api.post(`/api/students/${encodeURIComponent(studentCode)}/account`, normalizeStudentAccountInput(input)),
  );
}

export function getCurrentStudent() {
  return unwrap<Student>(api.get("/api/students/me"));
}

export function listFaceStatuses() {
  return unwrap<FaceDataStatus[]>(api.get("/api/face-data"));
}

export function enrollFace(studentCode: string, image: File, sampleLabel?: string, additionalSample = false) {
  const formData = new FormData();
  formData.append("image", image);
  if (sampleLabel) formData.append("sampleLabel", sampleLabel);
  formData.append("additionalSample", String(additionalSample));
  return unwrap<FaceDataStatus>(
    api.post(`/api/face-data/${encodeURIComponent(studentCode)}`, formData, {
      headers: { "Content-Type": "multipart/form-data" },
    }),
  );
}

export function deleteFaceData(studentCode: string) {
  return unwrap<FaceDataStatus>(api.delete(`/api/face-data/${encodeURIComponent(studentCode)}`));
}

function normalizeStudentInput(input: StudentInput, includeAdminFields: boolean) {
  const payload: Record<string, unknown> = {
    fullName: clean(input.fullName),
    gender: input.gender || null,
    dateOfBirth: clean(input.dateOfBirth),
    className: clean(input.className),
    schoolYear: clean(input.schoolYear),
    phone: clean(input.phone),
    email: clean(input.email),
    address: clean(input.address),
  };
  if (includeAdminFields) {
    payload.moralityScore = input.moralityScore;
    payload.active = input.active;
  }
  return payload;
}

function normalizeStudentAccountInput(input: StudentAccountInput) {
  return {
    username: clean(input.username),
    email: clean(input.email),
    password: input.password,
    confirmPassword: input.confirmPassword,
  };
}

function clean(value: unknown) {
  return typeof value === "string" && value.trim() === "" ? null : value;
}
