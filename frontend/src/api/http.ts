import axios, { AxiosError, AxiosResponse } from "axios";
import type { ApiEnvelope } from "../types";

const isProductionHost = window.location.hostname === "uniform.pawcarestore.online";

export const API_BASE_URL = stripTrailingSlash(
  import.meta.env.VITE_API_BASE_URL || (isProductionHost ? "https://api.pawcarestore.online" : "http://localhost:8080"),
);
export const AI_MEDIA_BASE_URL = stripTrailingSlash(
  import.meta.env.VITE_AI_MEDIA_BASE_URL || (isProductionHost ? "https://ai.pawcarestore.online" : API_BASE_URL),
);

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
    } else if (status === 404) {
      message = error.response?.data?.message || "Không tìm thấy tài nguyên yêu cầu.";
    } else if (error.code === "ECONNABORTED" || error.code === "ETIMEDOUT") {
      message = "Yêu cầu xử lý quá thời gian chờ. Vui lòng thử lại.";
    } else if (!error.response) {
      message = "Không thể kết nối tới máy chủ. Vui lòng kiểm tra kết nối và thử lại.";
    } else if (status === 502 || status === 503 || status === 504) {
      message = error.response?.data?.message || "Dịch vụ máy chủ hoặc AI hiện không sẵn sàng.";
    } else if (status && status >= 500) {
      message = error.response.data?.message || "Máy chủ đang gặp lỗi. Vui lòng thử lại sau.";
    } else {
      message =
        error.response?.data?.message ||
        "Yêu cầu không thành công. Vui lòng kiểm tra dữ liệu và thử lại.";
    }
    return Promise.reject(new Error(message));
  },
);

function stripTrailingSlash(value: string) {
  return value.replace(/\/+$/, "");
}

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
