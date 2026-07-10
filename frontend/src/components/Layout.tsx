import {
  IconCalendarWeek as CalendarWeek,
  IconChartBar as BarChart3,
  IconClipboardList as ClipboardList,
  IconDeviceComputerCamera as Webcam,
  IconFileAlert,
  IconHistory as History,
  IconList as Menu,
  IconLogout as LogOut,
  IconSchool as GraduationCap,
  IconUpload as UploadCloud,
  IconUsers as Users,
  IconX as X,
} from "@tabler/icons-react";
import { useMemo, useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import type { Role } from "../types";

const navItems: Array<{ to: string; label: string; icon: typeof BarChart3; roles: Role[] }> = [
  // { to: "/realtime-camera", label: "Real-time Camera", icon: Webcam, roles: ["ADMIN"] },
  { to: "/", label: "Tổng quan", icon: BarChart3, roles: ["ADMIN"] },
  { to: "/students", label: "Học sinh", icon: Users, roles: ["ADMIN"] },
  { to: "/upload", label: "Tải ảnh", icon: UploadCloud, roles: ["ADMIN"] },
  { to: "/compare", label: "So sánh kết quả", icon: ClipboardList, roles: ["ADMIN"] },
  { to: "/uniform-schedules", label: "Cấu hình lịch đồng phục", icon: CalendarWeek, roles: ["ADMIN"] },
  { to: "/history", label: "Lịch sử", icon: History, roles: ["ADMIN"] },
  { to: "/correction-requests", label: "Yêu cầu sửa đổi", icon: IconFileAlert, roles: ["ADMIN", "STUDENT"] },
  { to: "/student/dashboard", label: "Trang học sinh", icon: GraduationCap, roles: ["STUDENT"] },
  { to: "/student/uniform-schedule", label: "Lịch đồng phục", icon: CalendarWeek, roles: ["STUDENT"] },
];

export function Layout() {
  const [open, setOpen] = useState(false);
  const { session, logout } = useAuth();
  const location = useLocation();

  const visibleNavItems = useMemo(
    () => navItems.filter((item) => (session?.role ? item.roles.includes(session.role) : false)),
    [session?.role],
  );
  const title = visibleNavItems.find((item) => item.to === location.pathname)?.label ?? "Quản lý đồng phục";
  const roleLabel = session?.role === "STUDENT" ? "Học sinh" : "Quản trị viên";
  const accountName = session?.username || session?.email;

  return (
    <div className="app-shell">
      <aside className={`sidebar ${open ? "is-open" : ""}`}>
        <div className="brand-block">
          <div className="brand-mark">UL</div>
          <div>
            <strong>Uniform Lib</strong>
            <span>{session?.role === "STUDENT" ? "Cổng học sinh" : "Admin Console"}</span>
          </div>
          <button className="icon-button mobile-only" type="button" onClick={() => setOpen(false)} aria-label="Đóng menu">
            <X size={18} />
          </button>
        </div>

        <nav className="nav-list" aria-label="Điều hướng chính">
          {visibleNavItems.map((item) => {
            const Icon = item.icon;
            return (
              <NavLink key={item.to} to={item.to} end={item.to === "/"} onClick={() => setOpen(false)}>
                <Icon size={18} />
                <span>{item.label}</span>
              </NavLink>
            );
          })}
        </nav>

        <div className="sidebar-footer">
          <div className="user-chip">
            <span>{accountName}</span>
            <small>{roleLabel}</small>
          </div>
          <button className="button ghost full-width" type="button" onClick={logout}>
            <LogOut size={17} />
            Đăng xuất
          </button>
        </div>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <button className="icon-button mobile-only" type="button" onClick={() => setOpen(true)} aria-label="Mở menu">
            <Menu size={20} />
          </button>
          <div>
            <p className="eyebrow">Hệ thống nhận diện đồng phục</p>
            <h1>{title}</h1>
          </div>
        </header>
        <Outlet />
      </main>
    </div>
  );
}
