import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import PrivateRoute     from './components/PrivateRoute';
import LoginPage        from './pages/LoginPage';
import DashboardPage    from './pages/DashboardPage';
import OrderDetailPage  from './pages/OrderDetailPage';
import ReportsPage      from './pages/ReportsPage';

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
            <Route path="/reports"         element={<ReportsPage />} />
          </Route>

          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
