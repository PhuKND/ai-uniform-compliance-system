import { IconEye as Eye, IconSearch as Search } from "@tabler/icons-react";
import { FormEvent, useEffect, useState } from "react";
import { getHistoryDetail, searchHistory, type HistoryFilters } from "../api/history";
import { AuthenticatedImage } from "../components/AuthenticatedImage";
import { ComponentList } from "../components/ComponentList";
import { Modal } from "../components/Modal";
import { StatusBadge } from "../components/StatusBadge";
import type { EvaluationHistory, Page } from "../types";
import { formatDate, formatDateTime, methodLabel } from "../utils/format";

const EMPTY_FILTERS: HistoryFilters = {
  studentCode: "",
  studentName: "",
  className: "",
  method: "",
  status: "",
  fromDate: "",
  toDate: "",
};

export function HistoryPage() {
  const [filters, setFilters] = useState<HistoryFilters>(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [history, setHistory] = useState<Page<EvaluationHistory> | null>(null);
  const [detail, setDetail] = useState<EvaluationHistory | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load(nextPage = page) {
    setLoading(true);
    setError(null);
    try {
      setHistory(await searchHistory(filters, nextPage));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể tải lịch sử.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load(page);
  }, [page]);

  function submitSearch(event: FormEvent) {
    event.preventDefault();
    setPage(0);
    void load(0);
  }

  async function openDetail(id: number) {
    setError(null);
    try {
      setDetail(await getHistoryDetail(id));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể tải chi tiết.");
    }
  }

  return (
    <div className="page-content">
      <div className="page-toolbar">
        <div>
          <h2>Lịch sử đánh giá</h2>
          <p className="muted">Tra cứu kết quả chính thức đã lưu cùng ảnh đầu vào, ảnh xử lý và quyết định cuối.</p>
        </div>
      </div>

      <form className="filter-bar history" onSubmit={submitSearch}>
        <label>
          Mã học sinh
          <div className="input-with-icon">
            <Search size={17} />
            <input
              value={filters.studentCode}
              onChange={(event) => setFilters((current) => ({ ...current, studentCode: event.target.value }))}
            />
          </div>
        </label>
        <label>
          Tên học sinh
          <input
            value={filters.studentName}
            onChange={(event) => setFilters((current) => ({ ...current, studentName: event.target.value }))}
          />
        </label>
        <label>
          Lớp
          <input
            value={filters.className}
            onChange={(event) => setFilters((current) => ({ ...current, className: event.target.value }))}
          />
        </label>
        <label>
          Phương pháp
          <select
            value={filters.method}
            onChange={(event) => setFilters((current) => ({ ...current, method: event.target.value as HistoryFilters["method"] }))}
          >
            <option value="">Tất cả</option>
            <option value="METHOD_1_GROUNDING_DINO_SCHP_FLORENCE">Grounding DINO V2</option>
            <option value="METHOD_2_YOLOV8_SCHP_FLORENCE">YOLOv8 V2</option>
          </select>
        </label>
        <label>
          Trạng thái
          <select
            value={filters.status}
            onChange={(event) => setFilters((current) => ({ ...current, status: event.target.value as HistoryFilters["status"] }))}
          >
            <option value="">Tất cả</option>
            <option value="COMPLIANT">Đạt</option>
            <option value="NON_COMPLIANT">Không đạt</option>
            <option value="NEEDS_REVIEW">Cần xem lại</option>
          </select>
        </label>
        <label>
          Từ ngày
          <input
            type="datetime-local"
            value={filters.fromDate}
            onChange={(event) => setFilters((current) => ({ ...current, fromDate: event.target.value }))}
          />
        </label>
        <label>
          Đến ngày
          <input
            type="datetime-local"
            value={filters.toDate}
            onChange={(event) => setFilters((current) => ({ ...current, toDate: event.target.value }))}
          />
        </label>
        <button className="button secondary" type="submit">
          Lọc
        </button>
      </form>

      {error ? <div className="alert danger">{error}</div> : null}

      <section className="table-panel">
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Học sinh</th>
                <th>Thời gian</th>
                <th>Phương pháp</th>
                <th>Trạng thái</th>
                <th>Điểm lịch lớp</th>
                <th>Vi phạm</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={7}>Đang tải lịch sử...</td>
                </tr>
              ) : history?.content.length ? (
                history.content.map((item) => (
                  <tr key={item.id}>
                    <td>
                      <div className="identity-cell">
                        <strong>{item.studentName}</strong>
                        <span>
                          {item.studentCode} · {item.className ?? "Chưa có lớp"}
                        </span>
                      </div>
                    </td>
                    <td>{formatDateTime(item.createdAt)}</td>
                    <td>{methodLabel(item.selectedMethod)}</td>
                    <td>
                      <StatusBadge status={item.complianceStatus} />
                    </td>
                    <td>
                      <strong>{item.finalScore ?? "-"}</strong>
                      <br />
                      <span className="muted small">Trừ rèn luyện {item.deductedPoints}</span>
                    </td>
                    <td>{item.violationSummary || "Không ghi nhận"}</td>
                    <td>
                      <button className="icon-button" type="button" onClick={() => void openDetail(item.id)} aria-label="Xem chi tiết">
                        <Eye size={17} />
                      </button>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={7}>Không có lịch sử phù hợp.</td>
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
            Trang {(history?.number ?? page) + 1} / {history?.totalPages || 1}
          </span>
          <button
            className="button ghost"
            type="button"
            disabled={history?.last ?? true}
            onClick={() => setPage((value) => value + 1)}
          >
            Trang sau
          </button>
        </div>
      </section>

      {detail ? <HistoryDetailModal history={detail} onClose={() => setDetail(null)} /> : null}
    </div>
  );
}

interface HistoryDetailModalProps {
  history: EvaluationHistory;
  onClose: () => void;
}

function HistoryDetailModal({ history, onClose }: HistoryDetailModalProps) {
  const hasBlackShorts = hasAcceptedComponent(history, "quan_short_tay_den");
  const hasWhiteTrousers = hasAcceptedComponent(history, "quan_dai_trang");

  return (
    <Modal title={`Chi tiết đánh giá #${history.id}`} onClose={onClose} wide>
      <div className="detail-modal-grid">
        <div className="media-stack">
          <div>
            <h3>Ảnh trước AI</h3>
            <div className="media-frame">
              <AuthenticatedImage src={history.originalImageUrl} alt="Ảnh trước AI" />
            </div>
          </div>
          <div>
            <h3>Ảnh đã chọn</h3>
            <div className="media-frame">
              <AuthenticatedImage src={history.processedImageUrl} alt="Ảnh xử lý chính thức" />
            </div>
          </div>
        </div>

        <div className="detail-stack">
          <section className="panel tight">
            <div className="panel-header">
              <h3>Quyết định</h3>
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
              <span>Phương pháp</span>
              <strong>{methodLabel(history.selectedMethod)}</strong>
              <span>Điểm tuân thủ lịch lớp</span>
              <strong>{history.finalScore ?? "-"}</strong>
              <span>Điểm trừ rèn luyện tự động</span>
              <strong>{history.deductedPoints}</strong>
            </div>
          </section>

          <section className="panel tight">
            <h3>Nhận xét</h3>
            <p>{history.finalComment || history.aiComment || "Không có nhận xét."}</p>
            {history.adminNote ? <p className="muted">Ghi chú: {history.adminNote}</p> : null}
          </section>

          <section className="panel tight">
            <h3>Thành phần</h3>
            <ComponentList title="Hợp lệ" items={history.acceptedComponents} empty="Không có thành phần hợp lệ." />
            <ComponentList title="Thiếu" items={history.missingComponents} empty="Không thiếu thành phần." tone="warning" />
            <ComponentList title="Bị loại" items={history.rejectedComponents} empty="Không có thành phần bị loại." tone="danger" />
          </section>

          <section className="panel tight">
            <h3>Kiểm tra phụ</h3>
            <div className="check-grid">
              <span>Áo sơ mi trắng</span>
              <b>{history.hasWhiteShirt ? "Có" : "Không"}</b>
              <span>Áo đoàn</span>
              <b>{history.hasYouthUnionShirt ? "Có" : "Không"}</b>
              <span>Quần tây đen</span>
              <b>{history.hasBlackTrousers ? "Có" : "Không"}</b>
              <span>Quần short đen</span>
              <b>{hasBlackShorts ? "Có" : "Không"}</b>
              <span>Quần dài trắng</span>
              <b>{hasWhiteTrousers ? "Có" : "Không"}</b>
              <span>Khăn quàng đỏ</span>
              <b>{history.hasRedScarf ? "Có" : "Không"}</b>
              <span>Sơ vin</span>
              <b>{history.shirtTuckedIn == null ? "-" : history.shirtTuckedIn ? "Đạt" : "Chưa đạt"}</b>
              <span>Nhăn</span>
              <b>{history.clothesWrinkled == null ? "-" : history.clothesWrinkled ? "Có" : "Không"}</b>
              <span>Bẩn</span>
              <b>{history.clothesDirty == null ? "-" : history.clothesDirty ? "Có" : "Không"}</b>
              <span>Rách</span>
              <b>{history.clothesTorn == null ? "-" : history.clothesTorn ? "Có" : "Không"}</b>
            </div>
          </section>
        </div>
      </div>
    </Modal>
  );
}

function hasAcceptedComponent(history: EvaluationHistory, key: string) {
  return history.acceptedComponents?.some((item) => {
    if (typeof item === "string") {
      return item === key;
    }
    return item.class_name === key || item.label === key;
  }) ?? false;
}
