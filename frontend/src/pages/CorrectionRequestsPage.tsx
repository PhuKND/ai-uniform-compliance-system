import {
  IconCircleCheck,
  IconCircleX,
  IconEye,
  IconFileAlert,
  IconRefresh,
} from "@tabler/icons-react";
import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  approveCorrectionRequest,
  cancelCorrectionRequest,
  listCorrectionRequests,
  listMyCorrectionRequests,
  rejectCorrectionRequest,
} from "../api/correctionRequests";
import { AuthenticatedImage } from "../components/AuthenticatedImage";
import { Modal } from "../components/Modal";
import { useAuth } from "../context/AuthContext";
import type { CorrectionRequest, CorrectionStatus, Page, ResolveCorrectionRequestInput } from "../types";
import { formatDateTime } from "../utils/format";

const PAGE_SIZE = 20;

export function CorrectionRequestsPage() {
  const { session } = useAuth();
  const [page, setPage] = useState(0);
  const [requests, setRequests] = useState<Page<CorrectionRequest> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [detail, setDetail] = useState<CorrectionRequest | null>(null);
  const [resolveTarget, setResolveTarget] = useState<CorrectionRequest | null>(null);
  const [cancellingId, setCancellingId] = useState<number | null>(null);

  const isAdmin = session?.role === "ADMIN";

  async function load(nextPage = page) {
    setLoading(true);
    setError(null);
    try {
      setRequests(
        isAdmin
          ? await listCorrectionRequests(nextPage, PAGE_SIZE)
          : await listMyCorrectionRequests(nextPage, PAGE_SIZE),
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể tải danh sách yêu cầu sửa đổi.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load(page);
  }, [page, isAdmin]);

  async function cancelRequest(request: CorrectionRequest) {
    if (!window.confirm(`Bạn có chắc muốn hủy yêu cầu #${request.id}?`)) return;
    setCancellingId(request.id);
    setError(null);
    setNotice(null);
    try {
      await cancelCorrectionRequest(request.id);
      setNotice(`Đã hủy yêu cầu #${request.id}.`);
      await load(page);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể hủy yêu cầu sửa đổi.");
    } finally {
      setCancellingId(null);
    }
  }

  async function resolved(message: string) {
    setResolveTarget(null);
    setNotice(message);
    await load(page);
  }

  return (
    <div className="page-content">
      <div className="page-toolbar">
        <div>
          <h2>Yêu cầu sửa đổi</h2>
          <p className="muted">
            {isAdmin
              ? "Xem minh chứng và xử lý đề nghị điều chỉnh điểm trừ rèn luyện của học sinh."
              : "Theo dõi trạng thái, minh chứng và phản hồi của quản trị viên cho các yêu cầu đã gửi."}
          </p>
        </div>
        <button className="button secondary" type="button" onClick={() => void load(page)} disabled={loading}>
          <IconRefresh size={17} />
          Làm mới
        </button>
      </div>

      {!isAdmin ? (
        <section className="panel correction-guidance">
          <IconFileAlert size={22} />
          <div>
            <h3>Gửi yêu cầu từ chi tiết đánh giá</h3>
            <p className="muted">
              Mở một kết quả trong trang học sinh và chọn “Yêu cầu sửa đổi”; mã lịch sử và điểm hiện tại sẽ được điền tự động.
            </p>
          </div>
          <Link className="button primary" to="/student/dashboard">
            Xem lịch sử đánh giá
          </Link>
        </section>
      ) : null}

      {notice ? <div className="alert success">{notice}</div> : null}
      {error ? <div className="alert danger">{error}</div> : null}

      <section className="table-panel">
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Yêu cầu</th>
                <th>Học sinh</th>
                <th>Điểm trừ</th>
                <th>Lý do yêu cầu</th>
                <th>Minh chứng</th>
                <th>Trạng thái</th>
                <th>Thời gian tạo</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={8}>Đang tải yêu cầu sửa đổi...</td>
                </tr>
              ) : requests?.content.length ? (
                requests.content.map((request) => (
                  <tr key={request.id}>
                    <td>
                      <div className="identity-cell">
                        <strong>#{request.id}</strong>
                        <span>Lịch sử #{request.evaluationHistoryId}</span>
                      </div>
                    </td>
                    <td>
                      <div className="identity-cell">
                        <strong>{request.studentName}</strong>
                        <span>{request.studentCode}</span>
                      </div>
                    </td>
                    <td>
                      <div className="deduction-change">
                        <span>{displayPoints(request.deductionAtSubmission)}</span>
                        <strong>→ {displayPoints(request.requestedDeduction)}</strong>
                      </div>
                    </td>
                    <td>{request.reason}</td>
                    <td>{evidenceSummary(request)}</td>
                    <td>
                      <CorrectionStatusBadge status={request.status} />
                    </td>
                    <td>{formatDateTime(request.createdAt)}</td>
                    <td>
                      <div className="row-actions">
                        <button
                          className="icon-button"
                          type="button"
                          onClick={() => setDetail(request)}
                          aria-label={`Xem chi tiết yêu cầu #${request.id}`}
                        >
                          <IconEye size={17} />
                        </button>
                        {isAdmin && request.status === "PENDING" ? (
                          <button
                            className="button secondary small-button"
                            type="button"
                            onClick={() => setResolveTarget(request)}
                          >
                            Xem xét
                          </button>
                        ) : null}
                        {!isAdmin && request.status === "PENDING" ? (
                          <button
                            className="button danger small-button"
                            type="button"
                            disabled={cancellingId === request.id}
                            onClick={() => void cancelRequest(request)}
                          >
                            {cancellingId === request.id ? "Đang hủy..." : "Hủy yêu cầu"}
                          </button>
                        ) : null}
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={8}>Chưa có yêu cầu sửa đổi nào.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="pagination">
          <button className="button ghost" type="button" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>
            Trang trước
          </button>
          <span>
            Trang {(requests?.number ?? page) + 1} / {requests?.totalPages || 1}
          </span>
          <button
            className="button ghost"
            type="button"
            disabled={requests?.last ?? true}
            onClick={() => setPage((value) => value + 1)}
          >
            Trang sau
          </button>
        </div>
      </section>

      {detail ? <CorrectionDetailModal request={detail} onClose={() => setDetail(null)} /> : null}
      {resolveTarget ? (
        <ResolveCorrectionModal
          request={resolveTarget}
          onClose={() => setResolveTarget(null)}
          onResolved={resolved}
        />
      ) : null}
    </div>
  );
}

function CorrectionDetailModal({ request, onClose }: { request: CorrectionRequest; onClose: () => void }) {
  return (
    <Modal title={`Yêu cầu sửa đổi #${request.id}`} onClose={onClose} wide>
      <div className="detail-modal-grid correction-detail-grid">
        <div className="media-stack">
          <div>
            <h3>Ảnh minh chứng</h3>
            {request.evidenceImageUrl ? (
              <div className="media-frame">
                <AuthenticatedImage src={request.evidenceImageUrl} alt={`Ảnh minh chứng yêu cầu #${request.id}`} />
              </div>
            ) : (
              <p className="muted">Không có ảnh minh chứng.</p>
            )}
          </div>
          <section className="panel tight">
            <h3>Ghi chú minh chứng</h3>
            <p>{request.evidenceNote || "Không có ghi chú minh chứng."}</p>
          </section>
        </div>

        <div className="detail-stack">
          <section className="panel tight">
            <div className="panel-header">
              <h3>Thông tin yêu cầu</h3>
              <CorrectionStatusBadge status={request.status} />
            </div>
            <div className="detail-list">
              <span>Mã yêu cầu</span>
              <strong>#{request.id}</strong>
              <span>Mã lịch sử đánh giá</span>
              <strong>#{request.evaluationHistoryId}</strong>
              <span>Học sinh</span>
              <strong>{request.studentName}</strong>
              <span>Mã học sinh</span>
              <strong>{request.studentCode}</strong>
              <span>Điểm trừ tại lúc gửi</span>
              <strong>{displayPoints(request.deductionAtSubmission)}</strong>
              <span>Điểm trừ đề nghị</span>
              <strong>{displayPoints(request.requestedDeduction)}</strong>
              <span>Điểm trừ sau xử lý</span>
              <strong>{displayPoints(request.deductionAfterDecision)}</strong>
              <span>Thời gian tạo</span>
              <strong>{formatDateTime(request.createdAt)}</strong>
              <span>Người xử lý</span>
              <strong>{request.resolvedBy ?? "-"}</strong>
              <span>Thời gian xử lý</span>
              <strong>{formatDateTime(request.resolvedAt)}</strong>
            </div>
          </section>

          <section className="panel tight">
            <h3>Lý do yêu cầu</h3>
            <p>{request.reason}</p>
          </section>

          <section className="panel tight">
            <h3>Phản hồi của quản trị viên</h3>
            <p>{request.adminResponseNote || "Chưa có phản hồi của quản trị viên."}</p>
          </section>
        </div>
      </div>
    </Modal>
  );
}

function ResolveCorrectionModal({
  request,
  onClose,
  onResolved,
}: {
  request: CorrectionRequest;
  onClose: () => void;
  onResolved: (message: string) => Promise<void>;
}) {
  const [form, setForm] = useState<ResolveCorrectionRequestInput>({
    adminResponseNote: "",
    updatedViolationSummary: "",
  });
  const [submitting, setSubmitting] = useState<"approve" | "reject" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const canSubmit = useMemo(() => request.status === "PENDING" && !submitting, [request.status, submitting]);

  async function submit(action: "approve" | "reject") {
    const actionLabel = action === "approve" ? "đồng ý" : "từ chối";
    if (!window.confirm(`Xác nhận ${actionLabel} yêu cầu #${request.id}?`)) return;
    setSubmitting(action);
    setError(null);
    try {
      if (action === "approve") {
        await approveCorrectionRequest(request.id, form);
        await onResolved(`Đã đồng ý yêu cầu #${request.id}.`);
      } else {
        await rejectCorrectionRequest(request.id, form);
        await onResolved(`Đã từ chối yêu cầu #${request.id}.`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể xử lý yêu cầu sửa đổi.");
    } finally {
      setSubmitting(null);
    }
  }

  function update<K extends keyof ResolveCorrectionRequestInput>(key: K, value: ResolveCorrectionRequestInput[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  return (
    <Modal title={`Xem xét yêu cầu #${request.id}`} onClose={onClose}>
      <div className="face-summary">
        <strong>{request.studentName}</strong>
        <span>
          Mã học sinh {request.studentCode} · Lịch sử #{request.evaluationHistoryId}
        </span>
        <span>
          Điểm trừ tại lúc gửi: <b>{displayPoints(request.deductionAtSubmission)}</b> · Đề nghị:{" "}
          <b>{displayPoints(request.requestedDeduction)}</b>
        </span>
        <CorrectionStatusBadge status={request.status} />
      </div>

      <div className="form-stack">
        <section className="panel tight">
          <h3>Lý do yêu cầu</h3>
          <p>{request.reason}</p>
          {request.evidenceNote ? <p className="muted">Ghi chú minh chứng: {request.evidenceNote}</p> : null}
        </section>
        <label>
          Phản hồi của quản trị viên
          <textarea
            rows={3}
            maxLength={5000}
            value={form.adminResponseNote ?? ""}
            onChange={(event) => update("adminResponseNote", event.target.value)}
          />
        </label>
        <label>
          Cập nhật nội dung vi phạm (không bắt buộc)
          <textarea
            rows={3}
            maxLength={5000}
            value={form.updatedViolationSummary ?? ""}
            onChange={(event) => update("updatedViolationSummary", event.target.value)}
          />
        </label>
        {error ? <div className="alert danger">{error}</div> : null}
        <div className="modal-actions correction-review-actions">
          <button className="button ghost" type="button" onClick={onClose} disabled={Boolean(submitting)}>
            Đóng
          </button>
          <button className="button danger" type="button" disabled={!canSubmit} onClick={() => void submit("reject")}>
            <IconCircleX size={17} />
            {submitting === "reject" ? "Đang từ chối..." : "Từ chối"}
          </button>
          <button className="button primary" type="button" disabled={!canSubmit} onClick={() => void submit("approve")}>
            <IconCircleCheck size={17} />
            {submitting === "approve" ? "Đang đồng ý..." : "Đồng ý"}
          </button>
        </div>
      </div>
    </Modal>
  );
}

function CorrectionStatusBadge({ status }: { status: CorrectionStatus }) {
  const className =
    status === "APPROVED" ? "success" : status === "REJECTED" || status === "CANCELLED" ? "danger" : "warning";
  return <span className={`badge ${className}`}>{correctionStatusLabel(status)}</span>;
}

function correctionStatusLabel(status: CorrectionStatus) {
  switch (status) {
    case "APPROVED":
      return "Đã đồng ý";
    case "REJECTED":
      return "Đã từ chối";
    case "CANCELLED":
      return "Đã hủy";
    case "PENDING":
    default:
      return "Chờ duyệt";
  }
}

function displayPoints(value: number | null) {
  return value == null ? "-" : `${value} điểm`;
}

function evidenceSummary(request: CorrectionRequest) {
  if (request.evidenceImageUrl && request.evidenceNote) return "Có ảnh và ghi chú";
  if (request.evidenceImageUrl) return "Có ảnh";
  if (request.evidenceNote) return "Có ghi chú";
  return "Không có";
}
