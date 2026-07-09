import { createContext, useContext, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login as apiLogin } from '../services/api';

const AuthContext = createContext(null);

// localStorage必须在首次渲染前同步读取：之前用useEffect恢复登录态，
// 但useEffect在首次渲染之后才执行 —— 刷新时第一帧isAuthenticated=false，
// PrivateRoute已经把用户踢回/login了，恢复来晚一步。惰性初始化没有这个竞态。
// Auth state must be read from localStorage synchronously, before the first
// render: the old useEffect-based restore ran after the first render, so on
// refresh PrivateRoute saw isAuthenticated=false for one frame and had already
// redirected to /login by the time the effect fired. Lazy useState initializers
// have no such race.
function restoreUser() {
  try {
    const stored = localStorage.getItem('user');
    return stored ? JSON.parse(stored) : null;
  } catch {
    return null; // corrupted entry - treat as logged out
  }
}

export function AuthProvider({ children }) {
  const [user,            setUser]            = useState(restoreUser);
  const [token,           setToken]           = useState(() => localStorage.getItem('token'));
  const [isAuthenticated, setIsAuthenticated] = useState(
      () => Boolean(localStorage.getItem('token') && restoreUser()));
  const navigate = useNavigate();

  const login = async (username, password) => {
    const data = await apiLogin(username, password);
    localStorage.setItem('token', data.token);
    localStorage.setItem('user',  JSON.stringify({ username: data.username, role: data.role }));
    setToken(data.token);
    setUser({ username: data.username, role: data.role });
    setIsAuthenticated(true);
    navigate('/dashboard');
  };

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    setToken(null);
    setUser(null);
    setIsAuthenticated(false);
    navigate('/login');
  };

  return (
    <AuthContext.Provider value={{ user, token, isAuthenticated, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
