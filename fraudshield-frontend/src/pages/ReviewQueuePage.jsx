import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { getReviewQueue } from '../services/api';
import NavBar from '../components/NavBar';
import RiskBadge from '../components/RiskBadge';
import LoadingSpinner from '../components/LoadingSpinner';

/**
 * 待审队列 — ops人员的工作入口：检测标记的订单在这里等待人工决定
 * Review queue: the ops entry point. Detection flags orders; humans resolve them here.
 * Rows link to the order detail page where the decision buttons live.
 */
export default function ReviewQueuePage() {
  const navigate = useNavigate();
  const [queue,   setQueue]   = useState([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState('');

  const fetchQueue = useCallback(async () => {
    try {
      setQueue(await getReviewQueue());
      setError('');
    } catch {
      setError('Failed to load the review queue.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchQueue();
    const id = setInterval(fetchQueue, 15000);
    return () => clearInterval(id);
  }, [fetchQueue]);

  // 队列中金额合计 — 用金额而不是件数向管理层描述风险敞口
  // Total $ awaiting decision — dollars communicate exposure better than counts
  const amountAtRisk = queue.reduce((sum, e) => sum + (e.amount ?? 0), 0);

  return (
    <div className="min-h-screen bg-dark-bg">
      <NavBar />

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-bold text-white">Review Queue</h1>
          {!loading && queue.length > 0 && (
            <div className="text-sm text-slate-400">
              <span className="text-white font-semibold">{queue.length}</span> pending ·
              <span className="text-amber-400 font-semibold ml-1.5">
                ${amountAtRisk.toFixed(2)}
              </span> at risk
            </div>
          )}
        </div>

        {error && (
          <div className="p-4 bg-red-500/10 border border-red-500/30 rounded-xl text-red-400 flex items-center justify-between">
            <span>{error}</span>
            <button onClick={fetchQueue} className="text-sm underline">Retry</button>
          </div>
        )}

        {loading ? <LoadingSpinner /> : (
          <div className="bg-dark-card border border-dark-border rounded-xl overflow-hidden">
            {queue.length === 0 ? (
              <p className="text-slate-500 text-sm text-center py-12">
                Queue is clear — no orders awaiting review. 🎉
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-dark-border text-slate-400 text-xs uppercase tracking-wide">
                      {['Detected', 'Order ID', 'User', 'Amount', 'Risk', 'Rules', ''].map((h, i) => (
                        <th key={i} className="px-4 py-3 text-left font-medium">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-dark-border">
                    {queue.map(e => (
                      <tr
                        key={e.id}
                        onClick={() => navigate(`/orders/${e.orderId}`)}
                        className="hover:bg-dark-bg/50 cursor-pointer transition-colors"
                      >
                        <td className="px-4 py-3 text-slate-400 whitespace-nowrap">
                          {e.detectedAt ? new Date(e.detectedAt).toLocaleString() : '—'}
                        </td>
                        <td className="px-4 py-3 font-mono text-xs text-slate-300">{e.orderId}</td>
                        <td className="px-4 py-3 text-slate-300">{e.userId}</td>
                        <td className="px-4 py-3 text-slate-300">${e.amount?.toFixed(2)}</td>
                        <td className="px-4 py-3"><RiskBadge riskLevel={e.riskLevel} /></td>
                        <td className="px-4 py-3 text-slate-400 text-xs">{e.triggeredRules?.join(', ')}</td>
                        <td className="px-4 py-3 text-indigo-400 text-xs whitespace-nowrap">Review →</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
