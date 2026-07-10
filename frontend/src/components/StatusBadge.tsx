import type { ComplianceStatus } from "../types";
import { STATUS_LABELS } from "../utils/format";

interface StatusBadgeProps {
  status?: ComplianceStatus | null;
}

export function StatusBadge({ status }: StatusBadgeProps) {
  if (!status) return <span className="badge neutral">Chưa có</span>;
  const className =
    status === "COMPLIANT" ? "success" : status === "NON_COMPLIANT" ? "danger" : "warning";
  return <span className={`badge ${className}`}>{STATUS_LABELS[status]}</span>;
}
