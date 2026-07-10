import { createContext, PropsWithChildren, useContext, useMemo, useState } from "react";
import { clearStoredToken, setStoredToken } from "../api/http";
import { login as loginRequest } from "../api/auth";
import type { AuthResponse, Role } from "../types";

const SESSION_KEY = "uniform_session";
const LEGACY_SESSION_KEY = "uniform_admin_session";

interface AuthContextValue {
  session: AuthResponse | null;
  authenticated: boolean;
  login: (email: string, password: string) => Promise<AuthResponse>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function homePathForRole(role?: Role | null) {
  return role === "STUDENT" ? "/student/dashboard" : "/";
}

export function AuthProvider({ children }: PropsWithChildren) {
  const [session, setSession] = useState<AuthResponse | null>(() => {
    const raw = localStorage.getItem(SESSION_KEY) ?? localStorage.getItem(LEGACY_SESSION_KEY);
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw) as AuthResponse;
      if (parsed.accessToken) {
        setStoredToken(parsed.accessToken);
      }
      return parsed;
    } catch {
      return null;
    }
  });

  async function login(email: string, password: string) {
    const response = await loginRequest(email, password);
    setStoredToken(response.accessToken);
    localStorage.setItem(SESSION_KEY, JSON.stringify(response));
    localStorage.removeItem(LEGACY_SESSION_KEY);
    setSession(response);
    return response;
  }

  function logout() {
    clearStoredToken();
    localStorage.removeItem(SESSION_KEY);
    localStorage.removeItem(LEGACY_SESSION_KEY);
    setSession(null);
  }

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      authenticated: Boolean(session?.accessToken),
      login,
      logout,
    }),
    [session],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside AuthProvider");
  return context;
}
