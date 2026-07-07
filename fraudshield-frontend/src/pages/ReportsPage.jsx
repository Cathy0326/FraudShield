import { useState, useEffect } from 'react';
import { getRecentEvents, getRulePrecision } from '../services/api';
import NavBar from '../components/NavBar';
import RiskBadge from '../components/RiskBadge';
import LoadingSpinner from '../components/LoadingSpinner';

// CSV导出：纯前端实现，无需后端
// CSV export: pure frontend — create a Blob and trigger a download link
function exportCsv(events) {
  const header = 'OrderId,UserId,IPAddress,Amount,RiskLevel,RiskScore,Rules,DetectedAt';
  const rows   = events.map(e =>
    [e.orderId, e.userId, e.ipAddress, e.amount, e.riskLevel,
     e.riskScore, e.triggeredRules?.join('|'), e.detectedAt].join(',')
  );
  const csv  = [header, ...rows].join('\n');
  const blob = new Blob([csv], { type: 'text/csv' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href     = url;
  a.download = `risk-report-${new Date().toISOString().split('T')[0]}.csv`;
  a.click();
  URL.revokeObjectURL(url); // 释放内存 / free the temporary URL
}

export default function ReportsPage() {
  const [startDate, setStartDate] = useState('');
  const [endDate,   setEndDate]   = useState('');
  const [events,    setEvents]    = useState([]);
  const [loading,   setLoading]   = useState(false);
  const [error,     setError]     = useState('');
  const [generated, setGenerated] = useState(false);
  const [rulePrecision, setRulePrecision] = useState([]);

  // 规则精度独立于日期报表加载 —— 它反映的是全部历史标注
  // Rule precision loads independently of the date report — it reflects all labels to date
  useEffect(() => {
    getRulePrecision().then(setRulePrecision).catch(() => {});
  }, []);

  const handleGenerate = async () => {
    setLoading(true);
    setError('');
    try {
      // Use recent events as a proxy; a real backend would accept date params
      const data = await getRecentEvents(50);
      setEvents(data);
      setGenerated(true);
    } catch {
      setError('Failed to generate report. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const high   = events.filter(e => e.riskLevel === 'HIGH').length;
  const medium = events.filter(e => e.riskLevel === 'MEDIUM').length;

  return (
    <div className="min-h-screen bg-dark-bg">
      <NavBar />
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-6">
        <h1 className="text-2xl font-bold text-white">Reports</h1>

        {/* Filters */}
        <div className="bg-dark-card border border-dark-border rounded-xl p-5">
          <h2 className="text-base font-semibold text-white mb-4">Date Range</h2>
          <div className="flex flex-wrap items-end gap-4">
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">From</label>
              <input type="date" value={startDate} onChange={e => setStartDate(e.target.value)}
                className="bg-dark-bg border border-dark-border rounded-lg px-3 py-2 text-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
            </div>
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">To</label>
              <input type="date" value={endDate} onChange={e => setEndDate(e.target.value)}
                className="bg-dark-bg border border-dark-border rounded-lg px-3 py-2 text-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
            </div>
            <button onClick={handleGenerate} disabled={loading}
              className="px-5 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors">
              {loading ? 'Generating…' : 'Generate Report'}
            </button>
            {generated && events.length > 0 && (
              <button onClick={() => exportCsv(events)}
                className="px-5 py-2 bg-green-700/40 hover:bg-green-700/60 text-green-300 border border-green-500/30 text-sm font-medium rounded-lg transition-colors">
                ⬇ Export CSV
              </button>
            )}
          </div>
        </div>

        {error && (
          <div className="p-4 bg-red-500/10 border border-red-500/30 rounded-xl text-red-400">{error}</div>
        )}

        {/* Rule Precision — 审核标注反哺规则质量评估 / review labels feeding back into rule quality */}
        <div className="bg-dark-card border border-dark-border rounded-xl overflow-hidden">
          <div className="px-5 py-4 border-b border-dark-border">
            <h2 className="text-base font-semibold text-white">Rule Precision</h2>
            <p className="text-xs text-slate-500 mt-1">
              Computed from review decisions — which rules are firing correctly, and which
              generate the most false alarms. Rules with the most wrong calls sort first.
            </p>
          </div>
          {rulePrecision.length === 0 ? (
            <p className="text-slate-500 text-sm text-center py-8">
              No rule hits recorded yet.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-dark-border text-slate-400 text-xs uppercase">
                    {['Rule', 'Total Hits', 'Reviewed', 'Confirmed Fraud', 'False Alarms', 'Precision'].map(h => (
                      <th key={h} className="px-4 py-3 text-left">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-dark-border">
                  {rulePrecision.map(r => {
                    const falseAlarms = (r.falsePositive ?? 0) + (r.approved ?? 0);
                    return (
                      <tr key={r.rule} className="hover:bg-dark-bg/40 transition-colors">
                        <td className="px-4 py-3 text-slate-200 font-medium">{r.rule}</td>
                        <td className="px-4 py-3 text-slate-300">{r.totalHits}</td>
                        <td className="px-4 py-3 text-slate-400">{r.reviewedHits}</td>
                        <td className="px-4 py-3 text-red-300">{r.confirmedFraud}</td>
                        <td className="px-4 py-3 text-amber-300">{falseAlarms}</td>
                        <td className="px-4 py-3">
                          {r.precision == null ? (
                            <span className="text-xs text-slate-500">no labels yet</span>
                          ) : (
                            <div className="flex items-center gap-2">
                              <div className="w-24 bg-dark-bg rounded-full h-2">
                                <div
                                  className="h-2 rounded-full"
                                  style={{
                                    width: `${r.precision}%`,
                                    background: r.precision >= 80 ? '#22c55e' :
                                                r.precision >= 50 ? '#f97316' : '#ef4444',
                                  }}
                                />
                              </div>
                              <span className={`text-xs font-semibold ${
                                r.precision >= 80 ? 'text-green-400' :
                                r.precision >= 50 ? 'text-orange-400' : 'text-red-400'
                              }`}>
                                {r.precision.toFixed(0)}%
                              </span>
                            </div>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {loading ? <LoadingSpinner /> : generated && (
          <>
            {/* Summary cards */}
            <div className="grid grid-cols-3 gap-4">
              {[
                { label: 'Total in Range', value: events.length },
                { label: 'High Risk',      value: high,   cls: 'border-red-500/30' },
                { label: 'Medium Risk',    value: medium, cls: 'border-orange-500/30' },
              ].map(c => (
                <div key={c.label} className={`bg-dark-card border ${c.cls ?? 'border-dark-border'} rounded-xl p-4`}>
                  <p className="text-xs text-slate-400">{c.label}</p>
                  <p className="text-2xl font-bold text-white mt-1">{c.value}</p>
                </div>
              ))}
            </div>

            {/* Results table */}
            <div className="bg-dark-card border border-dark-border rounded-xl overflow-hidden">
              <div className="px-5 py-4 border-b border-dark-border">
                <h2 className="text-base font-semibold text-white">
                  Report Results ({events.length} events)
                </h2>
              </div>
              {events.length === 0 ? (
                <p className="text-slate-500 text-center py-10 text-sm">No events found</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-dark-border text-slate-400 text-xs uppercase">
                        {['Time','Order ID','User','IP','Amount','Risk Level','Rules'].map(h => (
                          <th key={h} className="px-4 py-3 text-left">{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-dark-border">
                      {events.map(e => (
                        <tr key={e.id} className="hover:bg-dark-bg/40 transition-colors">
                          <td className="px-4 py-3 text-slate-400 whitespace-nowrap">
                            {e.detectedAt ? new Date(e.detectedAt).toLocaleString() : '—'}
                          </td>
                          <td className="px-4 py-3 font-mono text-xs text-slate-300">{e.orderId}</td>
                          <td className="px-4 py-3 text-slate-300">{e.userId}</td>
                          <td className="px-4 py-3 font-mono text-xs text-slate-400">{e.ipAddress}</td>
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
