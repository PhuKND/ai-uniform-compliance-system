import { IconCalendarWeek, IconRefresh } from "@tabler/icons-react";
import { useEffect, useState } from "react";
import { getMyUniformSchedule } from "../api/uniformSchedules";
import type { StudentUniformScheduleResponse } from "../types";

export function StudentUniformSchedulePage() {
  const [schedule, setSchedule] = useState<StudentUniformScheduleResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setSchedule(await getMyUniformSchedule());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể tải lịch đồng phục.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  return (
    <div className="page-content">
      <div className="page-toolbar">
        <div>
          <h2>Lịch đồng phục</h2>
          <p className="muted">
            {schedule ? `${schedule.studentName} · ${schedule.className ?? "Chưa có lớp"}` : "Lịch từ lớp của tài khoản học sinh"}
          </p>
        </div>
        <button className="button ghost" type="button" onClick={() => void load()} disabled={loading}>
          <IconRefresh size={17} />
          Làm mới
        </button>
      </div>

      {error ? <div className="alert danger">{error}</div> : null}
      {loading ? <div className="panel">Đang tải lịch đồng phục...</div> : null}

      {!loading && schedule ? (
        <section className="student-schedule-grid">
          {schedule.days.map((day) => (
            <article className={`student-schedule-day ${day.isToday ? "today" : ""}`} key={day.dayOfWeek}>
              <div className="schedule-day-header">
                <div>
                  <p className="eyebrow">{day.dayOfWeek}</p>
                  <h3>{day.displayName}</h3>
                </div>
                <div className="schedule-day-icons">
                  {day.isToday ? <span className="badge success">Hôm nay</span> : null}
                  <IconCalendarWeek size={18} />
                </div>
              </div>

              {day.hasSchedule ? (
                day.requiredComponents.length ? (
                  <div className="student-schedule-components">
                    {day.requiredComponents.map((component) => (
                      <span className="component-pill" key={component.code}>
                        <span className={`component-swatch ${component.code}`} />
                        {component.name}
                      </span>
                    ))}
                  </div>
                ) : (
                  <p className="muted">Không yêu cầu thành phần đồng phục.</p>
                )
              ) : (
                <p className="muted">Chưa cấu hình lịch đồng phục</p>
              )}
            </article>
          ))}
        </section>
      ) : null}
    </div>
  );
}
