import {
  IconCamera as Camera,
  IconEdit as Edit3,
  IconKey as KeyRound,
  IconPlus as Plus,
  IconSearch as Search,
  IconTrash as Trash2,
  IconUpload as UploadCloud,
  IconUserCheck as UserRoundCheck,
} from "@tabler/icons-react";
import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  createStudent,
  createStudentAccount,
  deactivateStudent,
  deleteFaceData,
  enrollFace,
  listFaceStatuses,
  listStudents,
  updateStudent,
  type StudentFilters,
} from "../api/students";
import { Modal } from "../components/Modal";
import type { FaceDataStatus, Gender, Page, Student, StudentAccountInput, StudentInput } from "../types";
import { compressImageBeforeUpload, formatBytes } from "../utils/imageCompression";
import { formatDate } from "../utils/format";

const EMPTY_STUDENT: StudentInput = {
  fullName: "",
  gender: "",
  dateOfBirth: "",
  className: "",
  schoolYear: "",
  phone: "",
  email: "",
  address: "",
  active: true,
};

export function StudentsPage() {
  const [filters, setFilters] = useState<StudentFilters>({ keyword: "", className: "", active: "" });
  const [page, setPage] = useState(0);
  const [students, setStudents] = useState<Page<Student> | null>(null);
  const [faces, setFaces] = useState<Record<string, FaceDataStatus>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<Student | null>(null);
  const [creating, setCreating] = useState(false);
  const [faceStudent, setFaceStudent] = useState<Student | null>(null);
  const [accountStudent, setAccountStudent] = useState<Student | null>(null);

  async function load(nextPage = page) {
    setLoading(true);
    setError(null);
    try {
      const [studentPage, faceStatuses] = await Promise.all([listStudents(filters, nextPage), listFaceStatuses()]);
      setStudents(studentPage);
      setFaces(Object.fromEntries(faceStatuses.map((status) => [status.studentCode, status])));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể tải danh sách học sinh.");
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

  async function saveStudent(input: StudentInput) {
    if (editing) {
      await updateStudent(editing.studentCode, input);
    } else {
      await createStudent(input);
    }
    setEditing(null);
    setCreating(false);
    setNotice("Đã lưu hồ sơ học sinh.");
    await load(page);
  }

  async function deactivate(student: Student) {
    if (!window.confirm(`Vô hiệu hóa hồ sơ ${student.fullName}?`)) return;
    await deactivateStudent(student.studentCode);
    setNotice("Đã vô hiệu hóa hồ sơ học sinh.");
    await load(page);
  }

  async function accountCreated(student: Student) {
    setAccountStudent(null);
    setNotice(`Đã tạo tài khoản đăng nhập cho ${student.fullName}.`);
    await load(page);
  }

  return (
    <div className="page-content">
      <div className="page-toolbar">
        <div>
          <h2>Hồ sơ học sinh</h2>
          <p className="muted">Quản lý thông tin cá nhân, lớp, ngày sinh, mã khuôn mặt và tài khoản đăng nhập.</p>
        </div>
        <button className="button primary" type="button" onClick={() => setCreating(true)}>
          <Plus size={17} />
          Thêm học sinh
        </button>
      </div>

      <form className="filter-bar" onSubmit={submitSearch}>
        <label>
          Tìm kiếm
          <div className="input-with-icon">
            <Search size={17} />
            <input
              placeholder="Tên, mã học sinh, email..."
              value={filters.keyword}
              onChange={(event) => setFilters((current) => ({ ...current, keyword: event.target.value }))}
            />
          </div>
        </label>
        <label>
          Lớp
          <input
            placeholder="Ví dụ: 10A1"
            value={filters.className}
            onChange={(event) => setFilters((current) => ({ ...current, className: event.target.value }))}
          />
        </label>
        <label>
          Trạng thái
          <select
            value={filters.active}
            onChange={(event) => setFilters((current) => ({ ...current, active: event.target.value }))}
          >
            <option value="">Tất cả</option>
            <option value="true">Đang hoạt động</option>
            <option value="false">Đã vô hiệu hóa</option>
          </select>
        </label>
        <button className="button secondary" type="submit">
          Lọc
        </button>
      </form>

      {notice ? <div className="alert success">{notice}</div> : null}
      {error ? <div className="alert danger">{error}</div> : null}

      <section className="table-panel">
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Học sinh</th>
                <th>Lớp</th>
                <th>Ngày sinh</th>
                <th>Điểm rèn luyện</th>
                <th>Khuôn mặt</th>
                <th>Tài khoản</th>
                <th>Trạng thái</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={8}>Đang tải dữ liệu...</td>
                </tr>
              ) : students?.content.length ? (
                students.content.map((student) => {
                  const face = faces[student.studentCode];
                  return (
                    <tr key={student.studentCode}>
                      <td>
                        <div className="identity-cell">
                          <strong>{student.fullName}</strong>
                          <span>
                            {student.studentCode} · Face ID {student.faceDataId}
                          </span>
                        </div>
                      </td>
                      <td>{student.className ?? "-"}</td>
                      <td>
                        {formatDate(student.dateOfBirth)}
                        <br />
                        <span className="muted small">{student.age != null ? `${student.age} tuổi` : "Chưa có tuổi"}</span>
                      </td>
                      <td>
                        <strong>{student.moralityScore}</strong>
                        <br />
                        <span className="muted small">{student.moralityLevel}</span>
                      </td>
                      <td>
                        {face?.enrolled ? (
                          <span className="badge success">
                            <UserRoundCheck size={14} />
                            {face.sampleCount} mẫu
                          </span>
                        ) : (
                          <span className="badge warning">Chưa có</span>
                        )}
                      </td>
                      <td>
                        {student.hasAccount ? (
                          <div className="account-cell">
                            <span className="badge success">
                              <UserRoundCheck size={14} />
                              Đã có tài khoản
                            </span>
                            <span className="muted small">{student.accountUsername ?? student.accountEmail}</span>
                          </div>
                        ) : (
                          <button className="button secondary small-button" type="button" onClick={() => setAccountStudent(student)}>
                            <KeyRound size={15} />
                            Tạo tài khoản
                          </button>
                        )}
                      </td>
                      <td>{student.active ? <span className="badge success">Hoạt động</span> : <span className="badge neutral">Tắt</span>}</td>
                      <td>
                        <div className="row-actions">
                          <button className="icon-button" type="button" onClick={() => setFaceStudent(student)} aria-label="Cập nhật khuôn mặt">
                            <Camera size={17} />
                          </button>
                          <button className="icon-button" type="button" onClick={() => setEditing(student)} aria-label="Sửa học sinh">
                            <Edit3 size={17} />
                          </button>
                          <button className="icon-button danger" type="button" onClick={() => void deactivate(student)} aria-label="Vô hiệu hóa">
                            <Trash2 size={17} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })
              ) : (
                <tr>
                  <td colSpan={8}>Không có học sinh phù hợp.</td>
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
            Trang {(students?.number ?? page) + 1} / {students?.totalPages || 1}
          </span>
          <button
            className="button ghost"
            type="button"
            disabled={students?.last ?? true}
            onClick={() => setPage((value) => value + 1)}
          >
            Trang sau
          </button>
        </div>
      </section>

      {creating || editing ? (
        <StudentFormModal
          student={editing}
          onClose={() => {
            setCreating(false);
            setEditing(null);
          }}
          onSave={saveStudent}
        />
      ) : null}

      {accountStudent ? (
        <StudentAccountModal
          student={accountStudent}
          onClose={() => setAccountStudent(null)}
          onCreated={() => accountCreated(accountStudent)}
        />
      ) : null}

      {faceStudent ? (
        <FaceModal
          student={faceStudent}
          status={faces[faceStudent.studentCode]}
          onClose={() => setFaceStudent(null)}
          onChanged={() => load(page)}
        />
      ) : null}
    </div>
  );
}

interface StudentFormModalProps {
  student: Student | null;
  onClose: () => void;
  onSave: (input: StudentInput) => Promise<void>;
}

function StudentFormModal({ student, onClose, onSave }: StudentFormModalProps) {
  const initial = useMemo<StudentInput>(
    () =>
      student
        ? {
            fullName: student.fullName,
            gender: student.gender ?? "",
            dateOfBirth: student.dateOfBirth ?? "",
            className: student.className ?? "",
            schoolYear: student.schoolYear ?? "",
            phone: student.phone ?? "",
            email: student.email ?? "",
            address: student.address ?? "",
            moralityScore: student.moralityScore,
            active: student.active,
          }
        : EMPTY_STUDENT,
    [student],
  );
  const [form, setForm] = useState<StudentInput>(initial);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await onSave(form);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể lưu học sinh.");
    } finally {
      setSaving(false);
    }
  }

  function update<K extends keyof StudentInput>(key: K, value: StudentInput[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  return (
    <Modal title={student ? "Cập nhật học sinh" : "Thêm học sinh"} onClose={onClose} wide>
      <form className="form-grid" onSubmit={submit}>
        <label>
          Họ tên
          <input value={form.fullName ?? ""} onChange={(event) => update("fullName", event.target.value)} required />
        </label>
        <label>
          Giới tính
          <select value={form.gender ?? ""} onChange={(event) => update("gender", event.target.value as Gender | "")}>
            <option value="">Chưa chọn</option>
            <option value="MALE">Nam</option>
            <option value="FEMALE">Nữ</option>
            <option value="OTHER">Khác</option>
          </select>
        </label>
        <label>
          Ngày sinh
          <input type="date" value={form.dateOfBirth ?? ""} onChange={(event) => update("dateOfBirth", event.target.value)} />
        </label>
        <label>
          Lớp
          <input value={form.className ?? ""} onChange={(event) => update("className", event.target.value)} />
        </label>
        <label>
          Năm học
          <input value={form.schoolYear ?? ""} onChange={(event) => update("schoolYear", event.target.value)} />
        </label>
        <label>
          Điện thoại
          <input value={form.phone ?? ""} onChange={(event) => update("phone", event.target.value)} />
        </label>
        <label>
          Email
          <input type="email" value={form.email ?? ""} onChange={(event) => update("email", event.target.value)} />
        </label>
        {student ? (
          <label>
            Điểm rèn luyện
            <input
              type="number"
              min={0}
              max={100}
              value={form.moralityScore ?? 100}
              onChange={(event) => update("moralityScore", Number(event.target.value))}
            />
          </label>
        ) : null}
        <label className="full-span">
          Địa chỉ
          <textarea value={form.address ?? ""} onChange={(event) => update("address", event.target.value)} rows={3} />
        </label>
        {student ? (
          <label className="checkbox-line full-span">
            <input
              type="checkbox"
              checked={Boolean(form.active)}
              onChange={(event) => update("active", event.target.checked)}
            />
            Hồ sơ đang hoạt động
          </label>
        ) : null}
        {error ? <div className="alert danger full-span">{error}</div> : null}
        <div className="modal-actions full-span">
          <button className="button ghost" type="button" onClick={onClose}>
            Hủy
          </button>
          <button className="button primary" type="submit" disabled={saving}>
            {saving ? "Đang lưu..." : "Lưu"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

interface StudentAccountModalProps {
  student: Student;
  onClose: () => void;
  onCreated: () => Promise<void>;
}

function StudentAccountModal({ student, onClose, onCreated }: StudentAccountModalProps) {
  const initialUsername = student.studentCode.toLowerCase();
  const [form, setForm] = useState<StudentAccountInput>({
    username: initialUsername,
    email: student.email ?? `${initialUsername}@uniform.local`,
    password: "",
    confirmPassword: "",
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (!form.password.trim()) {
      setError("Vui lòng nhập mật khẩu.");
      return;
    }
    if (form.password.length < 6) {
      setError("Mật khẩu cần có ít nhất 6 ký tự.");
      return;
    }
    if (form.password !== form.confirmPassword) {
      setError("Mật khẩu xác nhận không khớp.");
      return;
    }

    setSaving(true);
    try {
      await createStudentAccount(student.studentCode, form);
      await onCreated();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể tạo tài khoản học sinh.");
    } finally {
      setSaving(false);
    }
  }

  function update<K extends keyof StudentAccountInput>(key: K, value: StudentAccountInput[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  return (
    <Modal title="Tạo tài khoản học sinh" onClose={onClose}>
      <div className="face-summary">
        <strong>{student.fullName}</strong>
        <span>
          {student.studentCode} · {student.className ?? "Chưa có lớp"}
        </span>
      </div>

      <form className="form-stack" onSubmit={submit}>
        <label>
          Tên đăng nhập
          <input value={form.username} onChange={(event) => update("username", event.target.value)} required />
        </label>
        <label>
          Email đăng nhập
          <input type="email" value={form.email} onChange={(event) => update("email", event.target.value)} required />
        </label>
        <label>
          Mật khẩu
          <input
            type="password"
            value={form.password}
            onChange={(event) => update("password", event.target.value)}
            placeholder="Ví dụ: 123456"
            required
          />
        </label>
        <label>
          Xác nhận mật khẩu
          <input
            type="password"
            value={form.confirmPassword ?? ""}
            onChange={(event) => update("confirmPassword", event.target.value)}
            required
          />
        </label>
        {error ? <div className="alert danger">{error}</div> : null}
        <div className="modal-actions">
          <button className="button ghost" type="button" onClick={onClose}>
            Hủy
          </button>
          <button className="button primary" type="submit" disabled={saving}>
            <KeyRound size={17} />
            {saving ? "Đang tạo..." : "Tạo tài khoản"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

interface FaceModalProps {
  student: Student;
  status?: FaceDataStatus;
  onClose: () => void;
  onChanged: () => Promise<void>;
}

function FaceModal({ student, status, onClose, onChanged }: FaceModalProps) {
  const [file, setFile] = useState<File | null>(null);
  const [sampleLabel, setSampleLabel] = useState("front");
  const [additionalSample, setAdditionalSample] = useState(Boolean(status?.enrolled));
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [working, setWorking] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!file) return;
    setWorking(true);
    setError(null);
    setMessage(null);
    try {
      const compressed = await compressImageBeforeUpload(file);
      await enrollFace(student.studentCode, compressed.file, sampleLabel, additionalSample);
      setMessage(
        `Đã gửi ảnh ${formatBytes(compressed.finalBytes)}${
          compressed.compressed ? ` sau khi nén từ ${formatBytes(compressed.originalBytes)}` : ""
        }.`,
      );
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể cập nhật khuôn mặt.");
    } finally {
      setWorking(false);
    }
  }

  async function removeFaceData() {
    if (!window.confirm(`Xóa dữ liệu khuôn mặt của ${student.fullName}?`)) return;
    setWorking(true);
    setError(null);
    try {
      await deleteFaceData(student.studentCode);
      await onChanged();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể xóa dữ liệu khuôn mặt.");
    } finally {
      setWorking(false);
    }
  }

  return (
    <Modal title="Dữ liệu khuôn mặt" onClose={onClose}>
      <div className="face-summary">
        <strong>{student.fullName}</strong>
        <span>
          {student.studentCode} · {student.faceDataId}
        </span>
        {status?.enrolled ? <span className="badge success">{status.sampleCount} mẫu đã đồng bộ</span> : <span className="badge warning">Chưa có mẫu</span>}
      </div>

      <form className="form-stack" onSubmit={submit}>
        <label>
          Ảnh khuôn mặt
          <input type="file" accept="image/*" onChange={(event) => setFile(event.target.files?.[0] ?? null)} required />
        </label>
        <label>
          Nhãn mẫu
          <select value={sampleLabel} onChange={(event) => setSampleLabel(event.target.value)}>
            <option value="front">Chính diện</option>
            <option value="left">Góc trái</option>
            <option value="right">Góc phải</option>
            <option value="other">Khác</option>
          </select>
        </label>
        <label className="checkbox-line">
          <input
            type="checkbox"
            checked={additionalSample}
            onChange={(event) => setAdditionalSample(event.target.checked)}
          />
          Thêm mẫu bổ sung thay vì ghi đè
        </label>
        {message ? <div className="alert success">{message}</div> : null}
        {error ? <div className="alert danger">{error}</div> : null}
        <div className="modal-actions">
          {status?.enrolled ? (
            <button className="button danger" type="button" onClick={() => void removeFaceData()} disabled={working}>
              Xóa dữ liệu
            </button>
          ) : null}
          <button className="button primary" type="submit" disabled={working || !file}>
            <UploadCloud size={17} />
            {working ? "Đang gửi..." : "Tải lên"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
