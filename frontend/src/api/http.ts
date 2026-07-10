import axios, { AxiosError, AxiosResponse } from "axios";
import type { ApiEnvelope } from "../types";

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
export const AI_MEDIA_BASE_URL = import.meta.env.VITE_AI_MEDIA_BASE_URL ?? API_BASE_URL;

const TOKEN_KEY = "uniform_access_token";
const LEGACY_TOKEN_KEY = "uniform_admin_token";

export const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 180000,
});

api.interceptors.request.use((config) => {
  const token = getStoredToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiEnvelope<unknown>>) => {
    const status = error.response?.status;
    let message: string;
    if (status === 401) {
      message = "Phiên đăng nhập không hợp lệ. Vui lòng đăng nhập lại.";
    } else if (status === 403) {
      message = "Bạn không có quyền thực hiện thao tác này.";
    } else if (status && status >= 500) {
      message = "Máy chủ đang gặp lỗi. Vui lòng thử lại sau.";
    } else {
      message =
        error.response?.data?.message ||
        error.message ||
        "Không thể kết nối tới máy chủ. Vui lòng thử lại.";
    }
    return Promise.reject(new Error(message));
  },
);

export function getStoredToken() {
  return localStorage.getItem(TOKEN_KEY) ?? localStorage.getItem(LEGACY_TOKEN_KEY);
}

export function setStoredToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.removeItem(LEGACY_TOKEN_KEY);
}

export function clearStoredToken() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(LEGACY_TOKEN_KEY);
}

export async function unwrap<T>(request: Promise<AxiosResponse<ApiEnvelope<T>>>): Promise<T> {
  const response = await request;
  const body = response.data;
  if (!body.success) {
    throw new Error(body.message || "Yêu cầu không thành công.");
  }
  return body.data;
}

export function withPageParams(page: number, size: number, extra: Record<string, unknown> = {}) {
  return {
    page,
    size,
    sort: "createdAt,desc",
    ...Object.fromEntries(Object.entries(extra).filter(([, value]) => value !== "" && value != null)),
  };
}
