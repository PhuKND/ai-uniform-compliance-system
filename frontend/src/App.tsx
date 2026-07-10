import { Navigate, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { ComparisonPage } from "./pages/ComparisonPage";
import { CorrectionRequestsPage } from "./pages/CorrectionRequestsPage";
import { DashboardPage } from "./pages/DashboardPage";
import { HistoryPage } from "./pages/HistoryPage";
import { LoginPage } from "./pages/LoginPage";
import { RealTimeCameraPage } from "./pages/RealTimeCameraPage";
import { StudentDashboardPage } from "./pages/StudentDashboardPage";
import { StudentUniformSchedulePage } from "./pages/StudentUniformSchedulePage";
import { StudentsPage } from "./pages/StudentsPage";
import { UniformSchedulePage } from "./pages/UniformSchedulePage";
import { UploadPage } from "./pages/UploadPage";

export function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<ProtectedRoute />}>
          <Route element={<Layout />}>
            <Route index element={<HomeRoute />} />

            <Route element={<ProtectedRoute allowedRoles={["ADMIN"]} />}>
              <Route path="students" element={<StudentsPage />} />
              <Route path="upload" element={<UploadPage />} />
              <Route path="realtime-camera" element={<RealTimeCameraPage />} />
              <Route path="compare" element={<ComparisonPage />} />
              <Route path="uniform-schedules" element={<UniformSchedulePage />} />
              <Route path="history" element={<HistoryPage />} />
            </Route>

            <Route element={<ProtectedRoute allowedRoles={["ADMIN", "STUDENT"]} />}>
              <Route path="correction-requests" element={<CorrectionRequestsPage />} />
            </Route>

            <Route element={<ProtectedRoute allowedRoles={["STUDENT"]} />}>
              <Route path="student/dashboard" element={<StudentDashboardPage />} />
              <Route path="student/uniform-schedule" element={<StudentUniformSchedulePage />} />
            </Route>
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  );
}

function HomeRoute() {
  const { session } = useAuth();
  if (session?.role === "STUDENT") {
    return <Navigate to="/student/dashboard" replace />;
  }
  return <DashboardPage />;
}
