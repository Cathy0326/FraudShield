import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import PrivateRoute     from './components/PrivateRoute';
import LoginPage        from './pages/LoginPage';
import DashboardPage    from './pages/DashboardPage';
import OrderDetailPage  from './pages/OrderDetailPage';
import DisputeEvidencePage from './pages/DisputeEvidencePage';
import ReviewQueuePage  from './pages/ReviewQueuePage';
import ReportsPage      from './pages/ReportsPage';
import AuditPage        from './pages/AuditPage';

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />

          {/* 受保护的路由 — 未登录跳转到 /login */}
          {/* Private routes — unauthenticated users are redirected to /login */}
          <Route element={<PrivateRoute />}>
            <Route path="/dashboard"       element={<DashboardPage />} />
            <Route path="/orders/:orderId" element={<OrderDetailPage />} />
            <Route path="/orders/:orderId/evidence" element={<DisputeEvidencePage />} />
            <Route path="/review"          element={<ReviewQueuePage />} />
            <Route path="/reports"         element={<ReportsPage />} />
            <Route path="/audit"           element={<AuditPage />} />
          </Route>

          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
