import { Navigate, Outlet, useLocation } from "react-router-dom";
import { homePathForRole, useAuth } from "../context/AuthContext";
import type { Role } from "../types";

interface ProtectedRouteProps {
  allowedRoles?: Role[];
}

export function ProtectedRoute({ allowedRoles }: ProtectedRouteProps) {
  const auth = useAuth();
  const location = useLocation();

  if (!auth.authenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (allowedRoles?.length && auth.session?.role && !allowedRoles.includes(auth.session.role)) {
    return <Navigate to={homePathForRole(auth.session.role)} replace />;
  }

  return <Outlet />;
}
