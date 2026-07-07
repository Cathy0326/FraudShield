import { NavLink } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function NavBar() {
  const { user, logout } = useAuth();

  const linkClass = ({ isActive }) =>
    `px-3 py-2 rounded text-sm font-medium transition-colors ${
      isActive ? 'bg-indigo-600 text-white' : 'text-slate-400 hover:text-white hover:bg-dark-card'
    }`;

  return (
    <nav className="bg-dark-card border-b border-dark-border px-6 py-3 flex items-center justify-between">
      <div className="flex items-center gap-6">
        <span className="text-indigo-400 font-bold text-lg tracking-tight">⚡ FraudShield</span>
        <NavLink to="/dashboard" className={linkClass}>Dashboard</NavLink>
        <NavLink to="/review"    className={linkClass}>Review Queue</NavLink>
        <NavLink to="/reports"   className={linkClass}>Reports</NavLink>
      </div>
      <div className="flex items-center gap-4">
        <span className="text-sm text-slate-400">
          <span className="text-slate-500">Logged in as </span>
          <span className="text-slate-200 font-medium">{user?.username}</span>
          <span className="ml-2 text-xs text-indigo-400 bg-indigo-900/40 px-2 py-0.5 rounded-full">
            {user?.role?.replace('ROLE_', '')}
          </span>
        </span>
        <button
          onClick={logout}
          className="text-sm px-3 py-1.5 bg-slate-700 hover:bg-slate-600 text-slate-200 rounded transition-colors"
        >
          Logout
        </button>
      </div>
    </nav>
  );
}
