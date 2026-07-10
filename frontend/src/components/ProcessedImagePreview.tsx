import { AuthenticatedImage } from "./AuthenticatedImage";

interface ProcessedImagePreviewProps {
  src?: string | null;
  alt: string;
}

export function ProcessedImagePreview({ src, alt }: ProcessedImagePreviewProps) {
  return (
    <div className="processed-image-preview">
      <AuthenticatedImage src={src} alt={alt} className="processed-preview-image" />
    </div>
  );
}
