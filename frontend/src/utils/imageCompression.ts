export interface CompressionResult {
  file: File;
  originalBytes: number;
  finalBytes: number;
  compressed: boolean;
}

const MAX_BYTES = 1024 * 1024;
const MIN_TARGET_BYTES = 768 * 1024;
const MAX_DIMENSION = 1920;

export async function compressImageBeforeUpload(file: File): Promise<CompressionResult> {
  if (!file.type.startsWith("image/")) {
    throw new Error("Vui lòng chọn tệp hình ảnh.");
  }

  if (file.size <= MAX_BYTES) {
    return {
      file,
      originalBytes: file.size,
      finalBytes: file.size,
      compressed: false,
    };
  }

  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, MAX_DIMENSION / Math.max(bitmap.width, bitmap.height));
  let width = Math.max(1, Math.round(bitmap.width * scale));
  let height = Math.max(1, Math.round(bitmap.height * scale));
  const canvas = document.createElement("canvas");
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Trình duyệt không hỗ trợ nén ảnh.");

  let quality = 0.88;
  let best: Blob | null = null;

  for (let attempt = 0; attempt < 12; attempt += 1) {
    canvas.width = width;
    canvas.height = height;
    context.clearRect(0, 0, width, height);
    context.drawImage(bitmap, 0, 0, width, height);
    const blob = await canvasToBlob(canvas, "image/jpeg", quality);
    best = blob;

    if (blob.size <= MAX_BYTES && blob.size >= MIN_TARGET_BYTES) break;
    if (blob.size <= MAX_BYTES && quality <= 0.62) break;

    if (blob.size > MAX_BYTES) {
      quality = Math.max(0.5, quality - 0.08);
      if (quality <= 0.56) {
        width = Math.round(width * 0.9);
        height = Math.round(height * 0.9);
      }
    } else {
      quality = Math.min(0.92, quality + 0.03);
      if (quality >= 0.9) break;
    }
  }

  bitmap.close();

  if (!best || best.size > MAX_BYTES) {
    throw new Error("Không thể nén ảnh xuống dưới 1 MB. Vui lòng chọn ảnh nhỏ hơn.");
  }

  const outputName = file.name.replace(/\.[^.]+$/, "") + ".jpg";
  return {
    file: new File([best], outputName, { type: "image/jpeg", lastModified: Date.now() }),
    originalBytes: file.size,
    finalBytes: best.size,
    compressed: true,
  };
}

function canvasToBlob(canvas: HTMLCanvasElement, type: string, quality: number) {
  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (!blob) {
          reject(new Error("Không thể tạo ảnh đã nén."));
          return;
        }
        resolve(blob);
      },
      type,
      quality,
    );
  });
}

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}
