import { useState, useEffect } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import CommandPalette from './CommandPalette';

export default function NavBar() {
  const { user, logout } = useAuth();
  const [paletteOpen, setPaletteOpen] = useState(false);

  // ⌘K / Ctrl+K 全局开关命令栏 —— 工具用户的肌肉记忆 / global ⌘K, the power-user reflex
  useEffect(() => {
    const onKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen(o => !o);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const linkClass = ({ isActive }) =>
    `px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
      isActive
        ? 'bg-indigo-500/90 text-white shadow-sm shadow-indigo-900/40'
        : 'text-slate-400 hover:text-white hover:bg-white/5'
    }`;

  return (
    <>
    <nav className="sticky top-0 z-30 bg-dark-card/70 backdrop-blur-md border-b border-white/5 px-6 py-3 flex items-center justify-between">
      <div className="flex items-center gap-6">
        <span className="text-lg font-bold tracking-tight bg-gradient-to-r from-indigo-400 to-violet-400 bg-clip-text text-transparent">
          ⚡ FraudShield
        </span>
        <NavLink to="/dashboard" className={linkClass}>Dashboard</NavLink>
        <NavLink to="/review"    className={linkClass}>Review Queue</NavLink>
        <NavLink to="/reports"   className={linkClass}>Reports</NavLink>
        <NavLink to="/audit"     className={linkClass}>Audit</NavLink>
      </div>
      <div className="flex items-center gap-4">
        {/* 命令栏入口：既是可点的搜索框，也把⌘K快捷键教给用户
            Command-bar entry — a clickable search affordance that also teaches the ⌘K shortcut */}
        <button
          onClick={() => setPaletteOpen(true)}
          className="hidden md:flex items-center gap-2 text-sm text-slate-500 hover:text-slate-300 bg-white/5 hover:bg-white/10 border border-white/10 rounded-lg pl-3 pr-2 py-1.5 transition-colors"
        >
          <span>Search or ask…</span>
          <kbd className="text-[10px] text-slate-500 border border-white/10 rounded px-1.5 py-0.5">⌘K</kbd>
        </button>
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
    <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
    </>
  );
}
