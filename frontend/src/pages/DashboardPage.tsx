import { IconRefresh as RefreshCw } from "@tabler/icons-react";
import { useEffect, useMemo, useState } from "react";
import { getAdminStatistics } from "../api/statistics";
import { MetricCard } from "../components/MetricCard";
import type { AdminStatistics } from "../types";
import { methodLabel, numberValue } from "../utils/format";

export function DashboardPage() {
  const [stats, setStats] = useState<AdminStatistics | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setStats(await getAdminStatistics());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể tải thống kê.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const maxClassCount = useMemo(() => {
    const values = Object.values(stats?.studentsByClass ?? {}).map((value) => numberValue(value));
    return Math.max(1, ...values);
  }, [stats]);

  return (
    <div className="page-content">
      <div className="page-toolbar">
        <div>
          <h2>Bảng điều khiển</h2>
          <p className="muted">Theo dõi học sinh, vi phạm, điểm rèn luyện và hiệu quả từng phương pháp AI.</p>
        </div>
        <button className="button secondary" type="button" onClick={() => void load()} disabled={loading}>
          <RefreshCw size={17} />
          Làm mới
        </button>
      </div>

      {error ? <div className="alert danger">{error}</div> : null}

      <section className="metrics-grid">
        <MetricCard label="Tổng học sinh" value={loading ? "..." : stats?.totalStudents ?? 0} detail="Hồ sơ đang quản lý" />
        <MetricCard
          label="Lượt đánh giá"
          value={loading ? "..." : stats?.totalEvaluations ?? 0}
          detail="Kết quả đã lưu chính thức"
        />
        <MetricCard
          label="Tổng vi phạm"
          value={loading ? "..." : stats?.totalViolations ?? 0}
          detail="Từ lịch sử đánh giá"
        />
        <MetricCard
          label="Yêu cầu chỉnh sửa"
          value={loading ? "..." : Object.values(stats?.correctionRequestStatusCounts ?? {}).reduce((a, b) => a + numberValue(b), 0)}
          detail="Tất cả trạng thái"
        />
      </section>

      <section className="dashboard-grid">
        <article className="panel">
          <div className="panel-header">
            <h3>Học sinh theo lớp</h3>
          </div>
          <div className="bar-list">
            {Object.entries(stats?.studentsByClass ?? {}).length === 0 ? (
              <p className="muted">Chưa có dữ liệu lớp.</p>
            ) : (
              Object.entries(stats?.studentsByClass ?? {}).map(([className, count]) => (
                <div className="bar-item" key={className}>
                  <div>
                    <span>{className}</span>
                    <strong>{count}</strong>
                  </div>
                  <div className="bar-track">
                    <span style={{ width: `${(numberValue(count) / maxClassCount) * 100}%` }} />
                  </div>
                </div>
              ))
            )}
          </div>
        </article>

        <article className="panel">
          <div className="panel-header">
            <h3>So sánh phương pháp</h3>
          </div>
          <div className="stat-list">
            {Object.entries(stats?.methodComparison ?? {}).length === 0 ? (
              <p className="muted">Chưa có kết quả chính thức.</p>
            ) : (
              Object.entries(stats?.methodComparison ?? {}).map(([method, count]) => (
                <div className="stat-row" key={method}>
                  <span>{methodLabel(method)}</span>
                  <strong>{count}</strong>
                </div>
              ))
            )}
          </div>
        </article>

        <article className="panel">
          <div className="panel-header">
            <h3>Vi phạm phổ biến</h3>
          </div>
          <div className="stat-list">
            {Object.entries(stats?.violationsByType ?? {}).length === 0 ? (
              <p className="muted">Chưa ghi nhận vi phạm.</p>
            ) : (
              Object.entries(stats?.violationsByType ?? {}).map(([type, count]) => (
                <div className="stat-row" key={type}>
                  <span>{type}</span>
                  <strong>{count}</strong>
                </div>
              ))
            )}
          </div>
        </article>

        <article className="panel">
          <div className="panel-header">
            <h3>Điểm rèn luyện thấp</h3>
          </div>
          <div className="compact-table">
            {(stats?.lowMoralityStudents ?? []).length === 0 ? (
              <p className="muted">Không có học sinh trong ngưỡng cảnh báo.</p>
            ) : (
              stats?.lowMoralityStudents?.map((student) => (
                <div className="compact-row" key={student.studentCode}>
                  <div>
                    <strong>{student.fullName}</strong>
                    <span>
                      {student.studentCode} · {student.className ?? "Chưa có lớp"}
                    </span>
                  </div>
                  <b>{student.moralityScore}</b>
                </div>
              ))
            )}
          </div>
        </article>
      </section>
    </div>
  );
}
