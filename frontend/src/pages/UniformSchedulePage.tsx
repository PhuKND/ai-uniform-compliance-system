import {
  IconAlertTriangle as AlertTriangle,
  IconCalendarWeek as CalendarWeek,
  IconDeviceFloppy as Save,
  IconRefresh as RefreshCw,
} from "@tabler/icons-react";
import { ChangeEvent, useEffect, useMemo, useState } from "react";
import {
  getUniformRequirementSchedule,
  listUniformClasses,
  updateUniformRequirementSchedule,
} from "../api/uniformSchedules";
import { Modal } from "../components/Modal";
import type {
  UniformClassOption,
  UniformComponentKey,
  UniformComponentOption,
  UniformRequirementScheduleResponse,
  Weekday,
} from "../types";
import { warningForScheduleToggle } from "../utils/scheduleWarnings";

const WEEKDAYS: Weekday[] = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];

const DEFAULT_COMPONENT_OPTIONS: UniformComponentOption[] = [
  { key: "ao_so_mi_trang", label: "áo sơ mi trắng" },
  { key: "ao_doan_thanh_nien", label: "áo đoàn thanh niên" },
  { key: "quan_tay_dai_den", label: "quần tây dài đen" },
  { key: "khan_quang_do", label: "khăn quàng đỏ" },
  { key: "quan_short_tay_den", label: "quần short đen" },
  { key: "quan_dai_trang", label: "quần dài trắng" },
];

type ScheduleDraft = Record<Weekday, UniformComponentKey[]>;

interface PendingWarning {
  dayOfWeek: Weekday;
  componentKey: UniformComponentKey;
  message: string;
}

export function UniformSchedulePage() {
  const [classes, setClasses] = useState<UniformClassOption[]>([]);
  const [selectedClassId, setSelectedClassId] = useState("");
  const [schedule, setSchedule] = useState<UniformRequirementScheduleResponse | null>(null);
  const [draft, setDraft] = useState<ScheduleDraft>(() => emptyDraft());
  const [savedKey, setSavedKey] = useState(() => draftKey(emptyDraft()));
  const [loadingClasses, setLoadingClasses] = useState(true);
  const [loadingSchedule, setLoadingSchedule] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pendingWarning, setPendingWarning] = useState<PendingWarning | null>(null);

  const componentOptions = schedule?.componentOptions?.length ? schedule.componentOptions : DEFAULT_COMPONENT_OPTIONS;
  const selectedClass = classes.find((item) => item.classId === selectedClassId) ?? null;
  const hasUnsavedChanges = useMemo(() => draftKey(draft) !== savedKey, [draft, savedKey]);

  useEffect(() => {
    let active = true;
    async function loadClasses() {
      setLoadingClasses(true);
      setError(null);
      try {
        const items = await listUniformClasses();
        if (!active) return;
        setClasses(items);
        setSelectedClassId((current) => current || items[0]?.classId || "");
      } catch (err) {
        if (active) setError(err instanceof Error ? err.message : "Không thể tải danh sách lớp.");
      } finally {
        if (active) setLoadingClasses(false);
      }
    }
    void loadClasses();
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!selectedClassId) {
      setSchedule(null);
      setDraft(emptyDraft());
      setSavedKey(draftKey(emptyDraft()));
      return undefined;
    }

    let active = true;
    async function loadSchedule() {
      setLoadingSchedule(true);
      setError(null);
      setNotice(null);
      try {
        const nextSchedule = await getUniformRequirementSchedule(selectedClassId);
        if (!active) return;
        const nextDraft = draftFromSchedule(nextSchedule);
        setSchedule(nextSchedule);
        setDraft(nextDraft);
        setSavedKey(draftKey(nextDraft));
      } catch (err) {
        if (active) setError(err instanceof Error ? err.message : "Không thể tải lịch đồng phục.");
      } finally {
        if (active) setLoadingSchedule(false);
      }
    }
    void loadSchedule();
    return () => {
      active = false;
    };
  }, [selectedClassId]);

  function changeClass(event: ChangeEvent<HTMLSelectElement>) {
    if (hasUnsavedChanges && !window.confirm("Bạn có thay đổi chưa lưu. Chuyển lớp và bỏ thay đổi hiện tại?")) {
      return;
    }
    setSelectedClassId(event.target.value);
  }

  function requestToggle(dayOfWeek: Weekday, componentKey: UniformComponentKey) {
    const current = draft[dayOfWeek] ?? [];
    const enabled = current.includes(componentKey);
    if (enabled) {
      applyToggle(dayOfWeek, componentKey, false);
      return;
    }

    const warning = warningForScheduleToggle(current, componentKey, true);
    if (warning) {
      setPendingWarning({ dayOfWeek, componentKey, message: warning });
      return;
    }
    applyToggle(dayOfWeek, componentKey, true);
  }

  function applyToggle(dayOfWeek: Weekday, componentKey: UniformComponentKey, enabled: boolean) {
    setDraft((current) => {
      const values = current[dayOfWeek] ?? [];
      const nextValues = enabled
        ? orderComponents([...values, componentKey])
        : values.filter((key) => key !== componentKey);
      return { ...current, [dayOfWeek]: nextValues };
    });
  }

  async function saveSchedule() {
    if (!selectedClassId) return;
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const nextSchedule = await updateUniformRequirementSchedule(selectedClassId, {
        schedules: WEEKDAYS.map((dayOfWeek) => ({
          dayOfWeek,
          requiredComponents: draft[dayOfWeek] ?? [],
        })),
      });
      const nextDraft = draftFromSchedule(nextSchedule);
      setSchedule(nextSchedule);
      setDraft(nextDraft);
      setSavedKey(draftKey(nextDraft));
      setNotice("Đã lưu cấu hình lịch đồng phục.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể lưu lịch đồng phục.");
    } finally {
      setSaving(false);
    }
  }

  function resetDraft() {
    if (!schedule) return;
    const nextDraft = draftFromSchedule(schedule);
    setDraft(nextDraft);
    setSavedKey(draftKey(nextDraft));
    setNotice(null);
  }

  return (
    <div className="page-content">
      <div className="page-toolbar">
        <div>
          <h2>Cấu hình lịch đồng phục</h2>
          <p className="muted">
            {selectedClass
              ? `${selectedClass.className} · ${selectedClass.studentCount} học sinh`
              : "Chưa có lớp từ hồ sơ học sinh"}
          </p>
        </div>
        <div className="toolbar-actions">
          <button className="button ghost" type="button" disabled={!hasUnsavedChanges || saving} onClick={resetDraft}>
            <RefreshCw size={17} />
            Hoàn tác
          </button>
          <button
            className="button primary"
            type="button"
            disabled={!selectedClassId || !hasUnsavedChanges || saving}
            onClick={() => void saveSchedule()}
          >
            <Save size={17} />
            {saving ? "Đang lưu" : "Lưu lịch"}
          </button>
        </div>
      </div>

      <section className="schedule-control-panel">
        <label>
          Lớp
          <select value={selectedClassId} onChange={changeClass} disabled={loadingClasses || saving}>
            {classes.map((item) => (
              <option key={item.classId} value={item.classId}>
                {item.className} ({item.studentCount})
              </option>
            ))}
          </select>
        </label>
        <div className="schedule-meta">
          <span className={`badge ${hasUnsavedChanges ? "warning" : "success"}`}>
            {hasUnsavedChanges ? "Chưa lưu" : "Đã đồng bộ"}
          </span>
          <span className="muted small">{schedule?.timeZone ?? "Asia/Ho_Chi_Minh"}</span>
        </div>
      </section>

      {notice ? <div className="alert success">{notice}</div> : null}
      {error ? <div className="alert danger">{error}</div> : null}
      {!loadingClasses && classes.length === 0 ? <div className="alert warning">Chưa có lớp trong hồ sơ học sinh.</div> : null}

      <section className="schedule-grid" aria-busy={loadingSchedule}>
        {WEEKDAYS.map((dayOfWeek) => {
          const daySchedule = schedule?.schedules.find((item) => item.dayOfWeek === dayOfWeek);
          const required = draft[dayOfWeek] ?? [];
          return (
            <article className="schedule-day-card" key={dayOfWeek}>
              <div className="schedule-day-header">
                <div>
                  <p className="eyebrow">{dayOfWeek}</p>
                  <h3>{daySchedule?.dayLabel ?? fallbackDayLabel(dayOfWeek)}</h3>
                </div>
                <CalendarWeek size={18} />
              </div>

              <div className="component-toggle-grid">
                {componentOptions.map((component) => {
                  const checked = required.includes(component.key);
                  return (
                    <label className={`component-toggle ${checked ? "checked" : ""}`} key={component.key}>
                      <input
                        type="checkbox"
                        checked={checked}
                        disabled={!selectedClassId || loadingSchedule || saving}
                        onChange={() => requestToggle(dayOfWeek, component.key)}
                      />
                      <span className={`component-swatch ${component.key}`} />
                      <span>{component.label}</span>
                    </label>
                  );
                })}
              </div>
            </article>
          );
        })}
      </section>

      {pendingWarning ? (
        <Modal title="Xác nhận yêu cầu bất thường" onClose={() => setPendingWarning(null)}>
          <div className="form-stack">
            <div className="alert warning">
              <AlertTriangle size={18} />
              {pendingWarning.message}
            </div>
            <div className="modal-actions">
              <button className="button ghost" type="button" onClick={() => setPendingWarning(null)}>
                Hủy
              </button>
              <button
                className="button primary"
                type="button"
                onClick={() => {
                  applyToggle(pendingWarning.dayOfWeek, pendingWarning.componentKey, true);
                  setPendingWarning(null);
                }}
              >
                Xác nhận
              </button>
            </div>
          </div>
        </Modal>
      ) : null}
    </div>
  );
}

function emptyDraft(): ScheduleDraft {
  return {
    MONDAY: [],
    TUESDAY: [],
    WEDNESDAY: [],
    THURSDAY: [],
    FRIDAY: [],
    SATURDAY: [],
    SUNDAY: [],
  };
}

function draftFromSchedule(schedule: UniformRequirementScheduleResponse): ScheduleDraft {
  const next = emptyDraft();
  for (const day of schedule.schedules) {
    next[day.dayOfWeek] = orderComponents(day.requiredComponents);
  }
  return next;
}

function draftKey(draft: ScheduleDraft) {
  return WEEKDAYS.map((day) => `${day}:${orderComponents(draft[day] ?? []).join(",")}`).join("|");
}

function orderComponents(values: UniformComponentKey[]) {
  const unique = Array.from(new Set(values));
  return DEFAULT_COMPONENT_OPTIONS.map((option) => option.key).filter((key) => unique.includes(key));
}

function fallbackDayLabel(dayOfWeek: Weekday) {
  return {
    MONDAY: "Thứ hai",
    TUESDAY: "Thứ ba",
    WEDNESDAY: "Thứ tư",
    THURSDAY: "Thứ năm",
    FRIDAY: "Thứ sáu",
    SATURDAY: "Thứ bảy",
    SUNDAY: "Chủ nhật",
  }[dayOfWeek];
}
