import { IconEye as Eye, IconEyeOff as EyeOff, IconShieldCheck as ShieldCheck } from "@tabler/icons-react";
import { FormEvent, useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { homePathForRole, useAuth } from "../context/AuthContext";

export function LoginPage() {
  const { authenticated, login, session } = useAuth();
  const [email, setEmail] = useState("admin@uniform.local");
  const [password, setPassword] = useState("");
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  if (authenticated) return <Navigate to={homePathForRole(session?.role)} replace />;

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const response = await login(email, password);
      const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;
      navigate(from ?? homePathForRole(response.role), { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Đăng nhập thất bại.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login-page">
      <section className="login-panel">
        <div className="login-brand">
          <div className="brand-mark large">
            <ShieldCheck size={34} />
          </div>
          <div>
            <p className="eyebrow">Uniform Lib</p>
            <h1>Quản lý đồng phục học sinh</h1>
          </div>
        </div>

        <form className="form-stack" onSubmit={handleSubmit}>
          <label>
            Email hoặc tên đăng nhập
            <input
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              autoComplete="username"
              required
            />
          </label>
          <label>
            Mật khẩu
            <div className="password-input">
              <input
                type={passwordVisible ? "text" : "password"}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="off"
                required
              />
              <button
                className="password-toggle"
                type="button"
                onClick={() => setPasswordVisible((visible) => !visible)}
                aria-label={passwordVisible ? "Ẩn mật khẩu" : "Hiện mật khẩu"}
                title={passwordVisible ? "Ẩn mật khẩu" : "Hiện mật khẩu"}
              >
                {passwordVisible ? <EyeOff size={19} /> : <Eye size={19} />}
              </button>
            </div>
          </label>
          {error ? <div className="alert danger">{error}</div> : null}
          <button className="button primary full-width" type="submit" disabled={submitting}>
            {submitting ? "Đang đăng nhập..." : "Đăng nhập"}
          </button>
        </form>
      </section>
    </main>
  );
}
