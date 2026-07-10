import {
  IconCalendar as CalendarDays,
  IconEye as Eye,
  IconFileAlert,
  IconHistory as History,
  IconId as IdCard,
  IconMail as Mail,
  IconRefresh as RefreshCw,
  IconSchool as School,
  IconSend,
  IconUserCircle as UserRound,
} from "@tabler/icons-react";
import { FormEvent, Fragment, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { createCorrectionRequest } from "../api/correctionRequests";
import { getMyHistory, getMyHistoryDetail } from "../api/history";
import { getCurrentStudent } from "../api/students";
import { AuthenticatedImage } from "../components/AuthenticatedImage";
import { ComponentList } from "../components/ComponentList";
import { MetricCard } from "../components/MetricCard";
import { Modal } from "../components/Modal";
import { StatusBadge } from "../components/StatusBadge";
import { useAuth } from "../context/AuthContext";
import type { CorrectionRequest, EvaluationHistory, Page, Student } from "../types";
import { asPercent, formatDate, formatDateTime, methodLabel, violationLabel } from "../utils/format";

const HISTORY_PAGE_SIZE = 8;

export function StudentDashboardPage() {
  const { session } = useAuth();
  const [student, setStudent] = useState<Student | null>(session?.student ?? null);
  const [history, setHistory] = useState<Page<EvaluationHistory> | null>(null);
  const [historyPage, setHistoryPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detailId, setDetailId] = useState<number | null>(null);
  const [detail, setDetail] = useState<EvaluationHistory | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  async function load(nextHistoryPage = historyPage) {
    setLoading(true);
    setError(null);
    try {
      const [profile, historyPageData] = await Promise.all([
        getCurrentStudent(),
        getMyHistory(nextHistoryPage, HISTORY_PAGE_SIZE),
      ]);
      setStudent(profile);
      setHistory(historyPageData);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể tải thông tin học sinh.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load(historyPage);
  }, [historyPage]);

  async function openDetail(id: number) {
    setDetailId(id);
    setDetail(null);
    setDetailError(null);
    setDetailLoading(true);
    try {
      setDetail(await getMyHistoryDetail(id));
    } catch (err) {
      setDetailError(err instanceof Error ? err.message : "Không thể tải chi tiết lịch sử đánh giá.");
    } finally {
      setDetailLoading(false);
    }
  }

  function closeDetail() {
    setDetailId(null);
    setDetail(null);
    setDetailError(null);
  }

  function refresh() {
    if (historyPage === 0) {
      void load(0);
      return;
    }
    setHistoryPage(0);
  }

  const accountUsername = student?.accountUsername ?? session?.username ?? "-";
  const accountEmail = student?.accountEmail ?? session?.email ?? "-";
  const historyItems = history?.content ?? [];

  return (
    <div className="page-content">
      <div className="page-toolbar">
        <div>
          <h2>Trang học sinh</h2>
          <p className="muted">Theo dõi thông tin cá nhân và kết quả đánh giá đồng phục của em.</p>
        </div>
        <button className="button secondary" type="button" onClick={refresh} disabled={loading}>
          <RefreshCw size={17} />
          Làm mới
        </button>
      </div>

      {error ? <div className="alert danger">{error}</div> : null}

      <section className="metrics-grid student-metrics">
        <MetricCard label="Điểm rèn luyện" value={loading ? "..." : student?.moralityScore ?? "-"} detail={student?.moralityLevel ?? "Chưa có dữ liệu"} />
        <MetricCard label="Lượt đánh giá" value={loading ? "..." : history?.totalElements ?? 0} detail="Lịch sử đánh giá đồng phục" />
        <MetricCard label="Lớp" value={student?.className ?? "-"} detail={student?.schoolYear ?? "Chưa có năm học"} />
        <MetricCard label="Tài khoản" value={accountUsername} detail={accountEmail} />
      </section>

      <section className="student-dashboard-grid">
        <article className="panel student-profile-panel">
          <div className="panel-header">
            <h3>Thông tin học sinh</h3>
            <UserRound size={20} />
          </div>
          <div className="student-profile-main">
            <div className="student-avatar" aria-hidden="true">
              {student?.fullName?.charAt(0).toUpperCase() ?? "H"}
            </div>
            <div>
              <h2>{student?.fullName ?? "Học sinh"}</h2>
              <p className="muted">{student?.studentCode ?? "-"}</p>
            </div>
          </div>
          <div className="student-info-list">
            <span>
              <IdCard size={16} />
              Mã học sinh
            </span>
            <strong>{student?.studentCode ?? "-"}</strong>
            <span>
              <School size={16} />
              Lớp
            </span>
            <strong>{student?.className ?? "-"}</strong>
            <span>
              <CalendarDays size={16} />
              Ngày sinh
            </span>
            <strong>
              {formatDate(student?.dateOfBirth)}
              {student?.age != null ? ` · ${student.age} tuổi` : ""}
            </strong>
            <span>
              <Mail size={16} />
              Email đăng nhập
            </span>
            <strong>{accountEmail}</strong>
          </div>
        </article>

        <article className="panel">
          <div className="panel-header">
            <h3>Lịch sử đánh giá đồng phục</h3>
            <History size={20} />
          </div>
          {loading ? (
            <p className="muted">Đang tải lịch sử đánh giá...</p>
          ) : historyItems.length === 0 ? (
            <p className="muted">Chưa có lịch sử đánh giá đồng phục.</p>
          ) : (
            <>
              <div className="student-history-list">
                {historyItems.map((item) => (
                  <div className="student-history-item" key={item.id}>
                    <div className="student-history-main">
                      <strong>Kết quả đánh giá #{item.id}</strong>
                      <span>
                        {formatDateTime(item.createdAt)} · {methodLabel(item.selectedMethod)}
                      </span>
                      <p>{item.violationSummary || "Không ghi nhận vi phạm"}</p>
                    </div>
                    <div className="student-history-status">
                      <StatusBadge status={item.complianceStatus} />
                      <small>Trừ rèn luyện {item.deductedPoints} điểm</small>
                      <button className="button ghost small-button" type="button" onClick={() => void openDetail(item.id)}>
                        <Eye size={15} />
                        Xem chi tiết
                      </button>
                    </div>
                  </div>
                ))}
              </div>

              {(history?.totalPages ?? 0) > 1 ? (
                <div className="pagination student-history-pagination">
                  <button className="button ghost" type="button" disabled={historyPage === 0} onClick={() => setHistoryPage((value) => Math.max(0, value - 1))}>
                    Trang trước
                  </button>
                  <span>
                    Trang {(history?.number ?? historyPage) + 1} / {history?.totalPages || 1}
                  </span>
                  <button className="button ghost" type="button" disabled={history?.last ?? true} onClick={() => setHistoryPage((value) => value + 1)}>
                    Trang sau
                  </button>
                </div>
              ) : null}
            </>
          )}
        </article>
      </section>

      {detailId != null ? (
        <StudentHistoryDetailModal
          requestedId={detailId}
          history={detail}
          loading={detailLoading}
          error={detailError}
          onClose={closeDetail}
        />
      ) : null}
    </div>
  );
}

interface StudentHistoryDetailModalProps {
  requestedId: number;
  history: EvaluationHistory | null;
  loading: boolean;
  error: string | null;
  onClose: () => void;
}

function StudentHistoryDetailModal({ requestedId, history, loading, error, onClose }: StudentHistoryDetailModalProps) {
  const titleId = history?.id ?? requestedId;

  if (loading) {
    return (
      <Modal title={`Chi tiết đánh giá #${titleId}`} onClose={onClose} wide>
        <p className="muted">Đang tải chi tiết...</p>
      </Modal>
    );
  }

  if (error) {
    return (
      <Modal title={`Chi tiết đánh giá #${titleId}`} onClose={onClose} wide>
        <div className="alert danger">{error}</div>
      </Modal>
    );
  }

  if (!history) {
    return (
      <Modal title={`Chi tiết đánh giá #${titleId}`} onClose={onClose} wide>
        <p className="muted">Không tìm thấy lịch sử đánh giá.</p>
      </Modal>
    );
  }

  const resultImage = history.selectedProcessedImageUrl ?? history.processedImageUrl ?? history.selectedProcessedImagePath;
  const aiImage = history.preAiImageUrl ?? history.preAiImagePath;
  const violations = Array.isArray(history.violationTypes) ? history.violationTypes : [];
  const appearanceDescription =
    history.appearanceAssessment?.model_description ?? history.appearanceAssessment?.description ?? null;
  const appearanceConfidence = history.appearanceAssessment?.confidence ?? null;

  return (
    <Modal title={`Chi tiết đánh giá #${history.id}`} onClose={onClose} wide>
      <div className="detail-modal-grid student-history-detail">
        <div className="media-stack student-detail-media">
          <DetailImage title="Ảnh đầu vào" src={history.originalImageUrl} alt="Ảnh đầu vào" />
          <DetailImage title="Ảnh kết quả" src={resultImage} alt="Ảnh kết quả đánh giá" />
          {aiImage ? <DetailImage title="Ảnh xử lý AI" src={aiImage} alt="Ảnh xử lý AI" /> : null}
        </div>

        <div className="detail-stack">
          <section className="panel tight">
            <div className="panel-header">
              <h3>Kết quả</h3>
              <StatusBadge status={history.complianceStatus} />
            </div>
            <div className="detail-list">
              <span>Học sinh</span>
              <strong>{history.studentName}</strong>
              <span>Mã học sinh</span>
              <strong>{history.studentCode}</strong>
              <span>Lớp</span>
              <strong>{history.className ?? "-"}</strong>
              <span>Ngày sinh</span>
              <strong>{formatDate(history.dateOfBirth)}</strong>
              <span>Tuổi khi đánh giá</span>
              <strong>{history.studentAgeAtEvaluation ?? "-"}</strong>
              <span>Thời gian đánh giá</span>
              <strong>{formatDateTime(history.createdAt)}</strong>
              <span>Phương pháp AI</span>
              <strong>{methodLabel(history.selectedMethod)}</strong>
              <span>Điểm tuân thủ lịch lớp</span>
              <strong>{history.finalScore ?? "-"}</strong>
              <span>Điểm trừ rèn luyện tự động</span>
              <strong>{history.deductedPoints}</strong>
              {history.recognizedStudentCode ? (
                <>
                  <span>AI nhận diện mã</span>
                  <strong>{history.recognizedStudentCode}</strong>
                </>
              ) : null}
            </div>
          </section>

          <CorrectionRequestForm history={history} />

          <section className="panel tight">
            <h3>Vi phạm</h3>
            {violations.length === 0 ? (
              <p className="muted">Không ghi nhận vi phạm.</p>
            ) : (
              <div className="violation-detail-list">
                {violations.map((code) => (
                  <div className="violation-detail-row" key={code}>
                    <div>
                      <strong>{violationLabel(code)}</strong>
                      <span>{code}</span>
                    </div>
                    <span className="badge warning">Cần xử lý</span>
                  </div>
                ))}
              </div>
            )}
            {history.violationSummary ? <p className="muted detail-note">{history.violationSummary}</p> : null}
          </section>

          <section className="panel tight">
            <h3>Nhận xét</h3>
            <div className="comment-stack">
              <p>{history.finalComment || history.aiComment || "Không có nhận xét."}</p>
              {appearanceDescription ? <p className="muted">Nhận xét ngoại hình: {appearanceDescription}</p> : null}
              {history.tuckInAssessment?.explanation ? (
                <p className="muted">Đánh giá sơ vin: {history.tuckInAssessment.explanation}</p>
              ) : null}
              {history.adminNote ? <p className="muted">Ghi chú giáo viên/admin: {history.adminNote}</p> : null}
            </div>
          </section>

          <section className="panel tight">
            <h3>Thành phần AI</h3>
            <ComponentList title="Đã nhận diện" items={history.acceptedComponents} empty="Không có thành phần được ghi nhận." />
            <ComponentList title="Thiếu" items={history.missingComponents} empty="Không thiếu thành phần." tone="warning" />
            <ComponentList title="Bị loại" items={history.rejectedComponents} empty="Không có thành phần bị loại." tone="danger" />
          </section>

          <section className="panel tight">
            <h3>Kiểm tra chi tiết</h3>
            <div className="check-grid">
              <span>Áo sơ mi trắng</span>
              <b>{yesNo(history.hasWhiteShirt)}</b>
              <span>Áo đoàn</span>
              <b>{yesNo(history.hasYouthUnionShirt)}</b>
              <span>Quần tây đen</span>
              <b>{yesNo(history.hasBlackTrousers)}</b>
              <span>Khăn quàng đỏ</span>
              <b>{yesNo(history.hasRedScarf)}</b>
              <span>Sơ vin</span>
              <b>{nullablePass(history.shirtTuckedIn)}</b>
              <span>Nhăn</span>
              <b>{nullableYesNo(history.clothesWrinkled)}</b>
              <span>Bẩn</span>
              <b>{nullableYesNo(history.clothesDirty)}</b>
              <span>Rách</span>
              <b>{nullableYesNo(history.clothesTorn)}</b>
              {history.tuckInAssessment?.confidence != null ? (
                <>
                  <span>Độ tin cậy sơ vin</span>
                  <b>{asPercent(history.tuckInAssessment.confidence)}</b>
                </>
              ) : null}
            </div>
          </section>

          {appearanceConfidence ? (
            <section className="panel tight">
              <h3>Độ tin cậy ngoại hình</h3>
              <div className="check-grid">
                {Object.entries(appearanceConfidence).map(([key, value]) => (
                  <Fragment key={key}>
                    <span>{violationLabel(key)}</span>
                    <b>{asPercent(Number(value))}</b>
                  </Fragment>
                ))}
              </div>
            </section>
          ) : null}
        </div>
      </div>
    </Modal>
  );
}

function CorrectionRequestForm({ history }: { history: EvaluationHistory }) {
  const [requestedDeduction, setRequestedDeduction] = useState(String(history.deductedPoints));
  const [reason, setReason] = useState("");
  const [evidenceNote, setEvidenceNote] = useState("");
  const [evidenceImage, setEvidenceImage] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submittedRequest, setSubmittedRequest] = useState<CorrectionRequest | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const normalizedDeduction = requestedDeduction.trim();
    if (!/^\d+$/.test(normalizedDeduction)) {
      setError("Điểm trừ đề nghị phải là số nguyên không âm.");
      return;
    }
    const points = Number(normalizedDeduction);
    if (!Number.isSafeInteger(points) || points < 0 || points > 100) {
      setError("Điểm trừ đề nghị phải là số nguyên từ 0 đến 100.");
      return;
    }
    if (!reason.trim()) {
      setError("Vui lòng nhập lý do yêu cầu.");
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const request = await createCorrectionRequest(
        history.id,
        points,
        reason.trim(),
        evidenceNote,
        evidenceImage,
      );
      setSubmittedRequest(request);
      setReason("");
      setEvidenceNote("");
      setEvidenceImage(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể gửi yêu cầu sửa đổi.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="panel tight correction-inline-panel">
      <div className="panel-header">
        <div>
          <h3>Yêu cầu sửa đổi</h3>
          <p className="muted">Đề nghị quản trị viên điều chỉnh tổng điểm trừ cuối cùng của đánh giá này.</p>
        </div>
        <IconFileAlert size={20} />
      </div>

      {submittedRequest ? (
        <div className="form-stack">
          <div className="alert success">
            Đã gửi yêu cầu sửa đổi #{submittedRequest.id} với {submittedRequest.requestedDeduction} điểm trừ đề nghị.
          </div>
          <Link className="button secondary" to="/correction-requests">
            Xem yêu cầu đã gửi
          </Link>
        </div>
      ) : (
        <form className="form-grid correction-inline-form" onSubmit={submit}>
          <label>
            Mã lịch sử đánh giá
            <input value={history.id} readOnly aria-readonly="true" />
          </label>
          <label>
            Điểm trừ rèn luyện hiện tại
            <input value={history.deductedPoints} readOnly aria-readonly="true" />
          </label>
          <label>
            Điểm trừ rèn luyện đề nghị
            <input
              type="number"
              min={0}
              max={100}
              step={1}
              inputMode="numeric"
              value={requestedDeduction}
              onChange={(event) => setRequestedDeduction(event.target.value)}
              required
            />
          </label>
          <label>
            Ảnh minh chứng (không bắt buộc)
            <input
              type="file"
              accept="image/*"
              onChange={(event) => setEvidenceImage(event.target.files?.[0] ?? null)}
            />
          </label>
          <label className="full-span">
            Lý do yêu cầu
            <textarea
              rows={3}
              maxLength={5000}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              required
            />
          </label>
          <label className="full-span">
            Ghi chú minh chứng (không bắt buộc)
            <textarea
              rows={2}
              maxLength={5000}
              value={evidenceNote}
              onChange={(event) => setEvidenceNote(event.target.value)}
            />
          </label>
          {error ? <div className="alert danger full-span">{error}</div> : null}
          <button className="button primary full-span" type="submit" disabled={submitting}>
            <IconSend size={17} />
            {submitting ? "Đang gửi yêu cầu..." : "Gửi yêu cầu sửa đổi"}
          </button>
        </form>
      )}
    </section>
  );
}

function DetailImage({ title, src, alt }: { title: string; src?: string | null; alt: string }) {
  return (
    <div>
      <h3>{title}</h3>
      <div className="media-frame">
        <AuthenticatedImage src={src} alt={alt} />
      </div>
    </div>
  );
}

function yesNo(value: boolean) {
  return value ? "Có" : "Không";
}

function nullableYesNo(value?: boolean | null) {
  if (value == null) return "-";
  return value ? "Có" : "Không";
}

function nullablePass(value?: boolean | null) {
  if (value == null) return "-";
  return value ? "Đạt" : "Chưa đạt";
}
