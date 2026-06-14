import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer,
  LineChart, Line, XAxis, YAxis, CartesianGrid,
  BarChart, Bar,
} from 'recharts';
import { getDashboardStats, getRecentEvents, triggerTestOrders } from '../services/api';
import NavBar from '../components/NavBar';
import RiskBadge from '../components/RiskBadge';
import LoadingSpinner from '../components/LoadingSpinner';

const PIE_COLORS = { HIGH: '#ef4444', MEDIUM: '#f97316', LOW: '#eab308', NORMAL: '#22c55e' };

function KpiCard({ label, value, sub, color }) {
  return (
    <div className={`bg-dark-card border border-dark-border rounded-xl p-5 ${color}`}>
      <p className="text-sm text-slate-400 mb-1">{label}</p>
      <p className="text-3xl font-bold text-white">{value ?? '—'}</p>
      {sub && <p className="text-xs text-slate-500 mt-1">{sub}</p>}
    </div>
  );
}

export default function DashboardPage() {
  const navigate = useNavigate();
  const [stats,    setStats]    = useState(null);
  const [events,   setEvents]   = useState([]);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState('');
  const [lastSync, setLastSync] = useState('');
  const [seeding,  setSeeding]  = useState(false);

  const fetchData = useCallback(async () => {
    try {
      const [s, e] = await Promise.all([getDashboardStats(), getRecentEvents(10)]);
      setStats(s);
      setEvents(e);
      setError('');
      setLastSync(new Date().toLocaleTimeString());
    } catch {
      setError('Failed to load data.');
    } finally {
      setLoading(false);
    }
  }, []);

  // 挂载时立即加载，之后每10秒自动刷新
  // Load on mount, then auto-refresh every 10 seconds
  useEffect(() => {
    fetchData();
    const id = setInterval(fetchData, 10000);
    return () => clearInterval(id); // 必须清理，防止内存泄漏 / must cleanup to prevent memory leak
  }, [fetchData]);

  const handleSeedOrders = async () => {
    setSeeding(true);
    try { await triggerTestOrders(); } catch { /* ignore */ }
    setTimeout(() => { fetchData(); setSeeding(false); }, 3000);
  };

  // KPI derived values
  const totalProcessed = stats ? (stats.totalOrders ?? 0) + (stats.normalCount ?? 0) : 0;
  const riskRateColor  = !stats ? '' :
    stats.riskRate > 20 ? 'border-red-500/30' :
    stats.riskRate > 5  ? 'border-yellow-500/30' : 'border-green-500/30';

  // Pie chart data
  const pieData = stats ? [
    { name: 'HIGH',   value: stats.highRiskCount   ?? 0 },
    { name: 'MEDIUM', value: stats.mediumRiskCount ?? 0 },
    { name: 'LOW',    value: stats.lowRiskCount    ?? 0 },
    { name: 'NORMAL', value: stats.normalCount     ?? 0 },
  ].filter(d => d.value > 0) : [];

  // Bar chart data from ruleHitCounts map
  const barData = stats?.ruleHitCounts
    ? Object.entries(stats.ruleHitCounts).map(([name, count]) => ({ name, count }))
    : [];

  // Hourly trend — show last 8 hours for readability
  const trendData = stats?.hourlyTrend?.slice(-8).map(h => ({
    hour:       h.hour.split(' ')[1],
    orders:     h.orderCount,
    riskOrders: h.riskCount,
  })) ?? [];

  return (
    <div className="min-h-screen bg-dark-bg">
      <NavBar />

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">

        {/* Header bar */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold text-white">Dashboard</h1>
            <span className="flex items-center gap-1.5 text-xs text-green-400 bg-green-900/30 px-2.5 py-1 rounded-full">
              <span className="w-1.5 h-1.5 bg-green-400 rounded-full animate-pulse" />
              LIVE
            </span>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-xs text-slate-500">Updated {lastSync || '—'}</span>
            <button
              onClick={handleSeedOrders}
              disabled={seeding}
              className="text-xs px-3 py-1.5 bg-indigo-600/30 hover:bg-indigo-600/50 text-indigo-300 border border-indigo-500/30 rounded-lg disabled:opacity-50 transition-colors"
            >
              {seeding ? 'Sending…' : '⚡ Send Test Orders'}
            </button>
          </div>
        </div>

        {error && (
          <div className="p-4 bg-red-500/10 border border-red-500/30 rounded-xl text-red-400 flex items-center justify-between">
            <span>{error}</span>
            <button onClick={fetchData} className="text-sm underline">Retry</button>
          </div>
        )}

        {loading ? <LoadingSpinner /> : (
          <>
            {/* Section A — KPI Cards */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
              <KpiCard label="Total Processed"  value={totalProcessed}          sub="orders" />
              <KpiCard label="High Risk"        value={stats?.highRiskCount}    sub="orders" color="border-red-500/30" />
              <KpiCard label="Medium Risk"      value={stats?.mediumRiskCount}  sub="orders" color="border-orange-500/30" />
              <KpiCard label="Risk Rate"        value={`${stats?.riskRate ?? 0}%`} color={riskRateColor} />
            </div>

            {/* Section B — Pie + Line charts */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <div className="bg-dark-card border border-dark-border rounded-xl p-5">
                <h2 className="text-base font-semibold text-white mb-4">Risk Distribution</h2>
                {pieData.length === 0 ? (
                  <p className="text-slate-500 text-sm text-center py-8">No data yet — send test orders</p>
                ) : (
                  <ResponsiveContainer width="100%" height={220}>
                    <PieChart>
                      <Pie data={pieData} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={80} label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`} labelLine={false}>
                        {pieData.map(d => <Cell key={d.name} fill={PIE_COLORS[d.name]} />)}
                      </Pie>
                      <Tooltip contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8 }} />
                      <Legend />
                    </PieChart>
                  </ResponsiveContainer>
                )}
              </div>

              <div className="bg-dark-card border border-dark-border rounded-xl p-5">
                <h2 className="text-base font-semibold text-white mb-4">Hourly Trend (last 8h)</h2>
                <ResponsiveContainer width="100%" height={220}>
                  <LineChart data={trendData}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
                    <XAxis dataKey="hour" tick={{ fill: '#94a3b8', fontSize: 11 }} />
                    <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} />
                    <Tooltip contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8 }} />
                    <Legend />
                    <Line type="monotone" dataKey="orders"     stroke="#6366f1" strokeWidth={2} dot={false} name="Total" />
                    <Line type="monotone" dataKey="riskOrders" stroke="#ef4444" strokeWidth={2} dot={false} name="Risk" />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </div>

            {/* Section C — Rule Hit Bar Chart */}
            {barData.length > 0 && (
              <div className="bg-dark-card border border-dark-border rounded-xl p-5">
                <h2 className="text-base font-semibold text-white mb-4">Rule Trigger Counts</h2>
                <ResponsiveContainer width="100%" height={180}>
                  <BarChart data={barData}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
                    <XAxis dataKey="name" tick={{ fill: '#94a3b8', fontSize: 12 }} />
                    <YAxis tick={{ fill: '#94a3b8', fontSize: 12 }} />
                    <Tooltip contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8 }} />
                    <Bar dataKey="count" fill="#6366f1" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}

            {/* Section D — Recent Events Table */}
            <div className="bg-dark-card border border-dark-border rounded-xl overflow-hidden">
              <div className="px-5 py-4 border-b border-dark-border">
                <h2 className="text-base font-semibold text-white">Recent Risk Events</h2>
              </div>
              {events.length === 0 ? (
                <p className="text-slate-500 text-sm text-center py-10">No risk events yet</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-dark-border text-slate-400 text-xs uppercase tracking-wide">
                        {['Time','Order ID','User','IP','Amount','Risk','Rules'].map(h => (
                          <th key={h} className="px-4 py-3 text-left font-medium">{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-dark-border">
                      {events.map(e => (
                        <tr
                          key={e.id}
                          onClick={() => navigate(`/orders/${e.orderId}`)}
                          className="hover:bg-dark-bg/50 cursor-pointer transition-colors"
                        >
                          <td className="px-4 py-3 text-slate-400 whitespace-nowrap">
                            {e.detectedAt ? new Date(e.detectedAt).toLocaleTimeString() : '—'}
                          </td>
                          <td className="px-4 py-3 font-mono text-xs text-slate-300">{e.orderId}</td>
                          <td className="px-4 py-3 text-slate-300">{e.userId}</td>
                          <td className="px-4 py-3 text-slate-400 font-mono text-xs">{e.ipAddress}</td>
                          <td className="px-4 py-3 text-slate-300">${e.amount?.toFixed(2)}</td>
                          <td className="px-4 py-3"><RiskBadge riskLevel={e.riskLevel} /></td>
                          <td className="px-4 py-3 text-slate-400 text-xs">{e.triggeredRules?.join(', ')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
