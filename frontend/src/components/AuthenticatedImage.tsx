import { IconPhotoOff as ImageOff } from "@tabler/icons-react";
import { useEffect, useMemo, useState } from "react";
import { AI_MEDIA_BASE_URL, API_BASE_URL, getStoredToken } from "../api/http";

interface AuthenticatedImageProps {
  src?: string | null;
  alt: string;
  className?: string;
}

export function AuthenticatedImage({ src, alt, className }: AuthenticatedImageProps) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  const resolved = useMemo(() => resolveMediaUrl(src), [src]);
  const protectedImage = Boolean(resolved && isBackendImage(resolved));

  useEffect(() => {
    setFailed(false);
    if (!resolved || !protectedImage) {
      setBlobUrl(null);
      return;
    }

    let cancelled = false;
    let objectUrl: string | null = null;
    const controller = new AbortController();
    setBlobUrl(null);
    const token = getStoredToken();

    fetch(resolved, {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      signal: controller.signal,
    })
      .then((response) => {
        if (!response.ok) throw new Error("Không thể tải ảnh.");
        return response.blob();
      })
      .then((blob) => {
        if (cancelled) return;
        if (blob.size === 0 || (blob.type && !blob.type.startsWith("image/"))) {
          throw new Error("Phản hồi ảnh không hợp lệ.");
        }
        objectUrl = URL.createObjectURL(blob);
        setBlobUrl(objectUrl);
      })
      .catch((error) => {
        if (!cancelled && error instanceof Error && error.name !== "AbortError") {
          logImageDiagnostic("Failed to load protected image", { src, resolved, error });
          setFailed(true);
        }
      });

    return () => {
      cancelled = true;
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [protectedImage, resolved]);

  if (!resolved || failed) {
    return (
      <div className={`image-empty ${className ?? ""}`}>
        <ImageOff size={28} />
        <span>Không có ảnh</span>
      </div>
    );
  }

  if (protectedImage && !blobUrl) {
    return (
      <div className={`image-empty ${className ?? ""}`}>
        <span>Đang tải ảnh...</span>
      </div>
    );
  }

  return (
    <img
      className={className}
      src={protectedImage ? blobUrl ?? undefined : resolved}
      alt={alt}
      onError={() => {
        logImageDiagnostic("Image failed to render", { src, resolved });
        setFailed(true);
      }}
    />
  );
}

function isBackendImage(src: string) {
  const normalized = normalizeMediaPath(src);
  if (!normalized) return false;
  if (normalized.startsWith("/api/images/") || normalized.startsWith(`${trimTrailingSlash(API_BASE_URL)}/api/images/`)) {
    return true;
  }
  try {
    return new URL(normalized).pathname.startsWith("/api/images/");
  } catch {
    return false;
  }
}

function resolveMediaUrl(src?: string | null) {
  const normalized = normalizeMediaPath(src);
  if (!normalized) return null;
  if (/^https?:\/\//i.test(normalized)) return isUnsafeBrowserMediaUrl(normalized) ? null : normalized;
  if (normalized.startsWith("/api/images/")) return safeMediaUrl(`${trimTrailingSlash(API_BASE_URL)}${normalized}`);
  if (normalized.startsWith("/api/uniform/") || normalized.startsWith("/static/")) {
    return safeMediaUrl(`${trimTrailingSlash(AI_MEDIA_BASE_URL)}${normalized}`);
  }
  if (normalized.startsWith("api/uniform/") || normalized.startsWith("static/")) {
    return safeMediaUrl(`${trimTrailingSlash(AI_MEDIA_BASE_URL)}/${normalized}`);
  }
  return safeMediaUrl(`${trimTrailingSlash(API_BASE_URL)}${normalized.startsWith("/") ? normalized : `/${normalized}`}`);
}

function normalizeMediaPath(src?: string | null) {
  if (!src) return null;
  const normalized = src.trim().replace(/\\/g, "/");
  if (!normalized || /^[A-Za-z]:\//.test(normalized)) return null;
  return normalized;
}

function trimTrailingSlash(value: string) {
  return value.endsWith("/") ? value.slice(0, -1) : value;
}

function safeMediaUrl(value: string) {
  return isUnsafeBrowserMediaUrl(value) ? null : value;
}

function isUnsafeBrowserMediaUrl(value: string) {
  try {
    const url = new URL(value);
    const host = url.hostname.toLowerCase();
    const localHost = isLocalHost(host);
    const currentHost = window.location.hostname.toLowerCase();
    const appIsLocal = isLocalHost(currentHost);
    return (window.location.protocol === "https:" && url.protocol === "http:") || (localHost && !appIsLocal);
  } catch {
    return false;
  }
}

function isLocalHost(host: string) {
  return (
    host === "localhost" ||
    host === "127.0.0.1" ||
    host === "0.0.0.0" ||
    host === "::1" ||
    host.startsWith("127.")
  );
}

function logImageDiagnostic(message: string, details: Record<string, unknown>) {
  if (import.meta.env.DEV) {
    console.error(message, details);
  }
}
