import { useState, useEffect, useRef, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { getFinancialImpact, getDashboardStats, getReviewQueue } from '../services/api';
import { useDictation } from '../hooks/useSpeech';

/**
 * ⌘K 指挥栏 —— 把"导航 / 按自然语言筛队列 / 问一句"收进一个已被工具用户预期的入口。
 * A ⌘K command bar folding three real workflows into one surface power users already
 * expect: jump between pages, describe a Review-Queue filter in plain words (so you
 * don't click three dropdowns), and ask a status question answered from live metrics.
 * Voice rides in as a secondary input on the same box — no separate "AI" surface.
 *
 * 每一项都必须"有用"：筛选项真正把参数带进Review Queue的既有筛选器；问答只答
 * 我们真有的数字，答不了就不显示 —— 不做花架子。
 * Every item has to earn its place: the filter command drives the queue's real
 * filters via URL params, and the ask answers only from metrics we actually have —
 * no theatrics.
 */

const usd = (n) => (n < 0 ? '-$' : '$') + Math.abs(Math.round(n ?? 0)).toLocaleString();

// 自然语言 → 队列筛选。只识别可靠的意图（风险等级、金额区间、明显的标识符），
// 认不出就返回null，绝不硬塞进搜索框制造噪声。
// Natural language → queue filter. Recognizes only the reliable intents (risk level,
// amount band, an obvious identifier); returns null otherwise rather than jamming
// leftover words into search.
function parseQueueFilter(raw) {
  const s = raw.toLowerCase();
  const risk = /\bhigh\b/.test(s) ? 'HIGH'
    : /\bmedium\b/.test(s) ? 'MEDIUM'
    : /\blow\b/.test(s) ? 'LOW' : 'ALL';

  let amount = 'ALL';
  const over = s.match(/(?:over|above|>|more than|greater than)\s*\$?\s*(\d+)/);
  const under = s.match(/(?:under|below|<|less than)\s*\$?\s*(\d+)/);
  if (over) { const n = +over[1]; amount = n >= 500 ? 'LARGE' : n >= 100 ? 'MEDIUM' : 'SMALL'; }
  else if (under) { const n = +under[1]; amount = n <= 10 ? 'MICRO' : n <= 100 ? 'SMALL' : 'MEDIUM'; }
  else if (/\b(micro|tiny)\b/.test(s)) amount = 'MICRO';
  else if (/\b(large|big|high[- ]value)\b/.test(s)) amount = 'LARGE';

  // 标识符样式的token（订单号/用户/IP）直接进搜索 / identifier-like token → search
  const id = raw.match(/(USER-[\w-]+|ORD-[\w-]+|\d{1,3}(?:\.\d{1,3}){3}|DEV-[\w-]+)/i);
  const q = id ? id[1] : '';

  if (risk === 'ALL' && amount === 'ALL' && !q) return null;
  const AMOUNT_LABEL = { MICRO: 'under $10', SMALL: '$10–100', MEDIUM: '$100–500', LARGE: 'over $500' };
  const bits = [risk !== 'ALL' && risk, amount !== 'ALL' && AMOUNT_LABEL[amount], q && `“${q}”`].filter(Boolean);
  const params = new URLSearchParams();
  if (risk !== 'ALL') params.set('risk', risk);
  if (amount !== 'ALL') params.set('amount', amount);
  if (q) params.set('q', q);
  return { label: `Review Queue · ${bits.join(' · ')}`, to: `/review?${params.toString()}` };
}

// 问答意图 —— 用已有指标回答，口吻沿用产品一贯的"结论优先"
// Ask intents, answered from metrics we already expose, in the house "so what" voice
function buildAnswer(raw, data) {
  const s = raw.toLowerCase();
  if (!data.impact && !data.stats) return null;
  const i = data.impact ?? {};
  const net = (i.interceptedAmount ?? 0) - (i.falsePositiveAmount ?? 0);

  if (/\b(win|winning|net|protect|worth|roi)\b/.test(s) && data.impact) {
    const ratio = i.interceptToFalseKillRatio;
    return `Net protected ${usd(net)}${ratio != null ? ` — ${ratio.toFixed(1)}× fraud caught per $1 of good revenue lost` : ''}.`;
  }
  if (/\b(risk rate|risk %|how risky)\b/.test(s) && data.stats) {
    return `Risk rate is ${data.stats.riskRate ?? 0}% of all processed orders.`;
  }
  if (/\b(queue|pending|backlog|exposure|to review)\b/.test(s)) {
    const n = data.queue?.length ?? 0;
    const exp = (data.queue ?? []).reduce((a, e) => a + (e.riskScore ?? 0) * (e.amount ?? 0), 0);
    return `${n} orders pending — ${usd(exp)} expected loss awaiting a decision.`;
  }
  if (/\b(intercept|blocked|fraud caught|losses avoided)\b/.test(s) && data.impact) {
    return `${i.interceptedCount ?? 0} confirmed-fraud orders intercepted — ${usd(i.interceptedAmount)} in losses avoided.`;
  }
  return null;
}

const NAV = [
  { label: 'Dashboard',    hint: 'mission control', to: '/dashboard' },
  { label: 'Review Queue', hint: 'triage flagged orders', to: '/review' },
  { label: 'Reports',      hint: 'rule health · finance · trends', to: '/reports' },
  { label: 'Audit Trail',  hint: 'tamper-evident decision log', to: '/audit' },
];

export default function CommandPalette({ open, onClose }) {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [sel, setSel] = useState(0);
  const [answer, setAnswer] = useState('');
  const [data, setData] = useState({ impact: null, stats: null, queue: null });
  const inputRef = useRef(null);

  const dictation = useDictation({ onResult: (t) => setQuery(q => (q ? q + ' ' : '') + t) });

  // 打开时抓一次问答所需的指标 —— 轻量、各自兜底 / fetch ask-metrics once on open
  useEffect(() => {
    if (!open) return;
    setQuery(''); setSel(0); setAnswer('');
    setTimeout(() => inputRef.current?.focus(), 30);
    getFinancialImpact().then(d => setData(p => ({ ...p, impact: d }))).catch(() => {});
    getDashboardStats().then(d => setData(p => ({ ...p, stats: d }))).catch(() => {});
    getReviewQueue().then(d => setData(p => ({ ...p, queue: d }))).catch(() => {});
  }, [open]);

  // 组装候选项：匹配的导航 + 解析出的筛选 + 命中的问答 / assemble candidates
  const items = useMemo(() => {
    const q = query.trim().toLowerCase();
    const nav = NAV
      .filter(n => !q || n.label.toLowerCase().includes(q) || n.hint.includes(q))
      .map(n => ({ kind: 'nav', ...n, run: () => { navigate(n.to); onClose(); } }));

    const list = [...nav];
    const filter = query.trim() ? parseQueueFilter(query) : null;
    if (filter) {
      list.unshift({ kind: 'filter', label: filter.label, hint: 'open filtered queue',
        run: () => { navigate(filter.to); onClose(); } });
    }
    const ans = query.trim() ? buildAnswer(query, data) : null;
    if (ans) {
      list.push({ kind: 'ask', label: ans, hint: 'answer',
        run: () => setAnswer(ans) });
    }
    return list;
  }, [query, data, navigate, onClose]);

  // 选中项越界时夹回 / keep selection in range as items change
  useEffect(() => { setSel(s => Math.min(s, Math.max(0, items.length - 1))); }, [items.length]);

  if (!open) return null;

  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setSel(s => Math.min(s + 1, items.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setSel(s => Math.max(s - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); items[sel]?.run(); }
    else if (e.key === 'Escape') { e.preventDefault(); onClose(); }
  };

  const KIND = {
    nav:    { icon: '→', c: '#818cf8' },
    filter: { icon: '⛃', c: '#fb923c' },
    ask:    { icon: '✦', c: '#34d399' },
  };

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center pt-[12vh] px-4"
         onClick={onClose}>
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" />
      <div className="relative w-full max-w-xl rounded-2xl border border-white/10 bg-dark-card/95 backdrop-blur-md shadow-2xl shadow-black/50 overflow-hidden"
           onClick={e => e.stopPropagation()}>
        <div className="flex items-center gap-3 px-4 py-3 border-b border-white/5">
          <span className="text-slate-500">⌘</span>
          <input
            ref={inputRef}
            value={query}
            onChange={e => { setQuery(e.target.value); setAnswer(''); }}
            onKeyDown={onKeyDown}
            placeholder="Jump to a page, filter the queue (“high orders over $500”), or ask “are we winning?”"
            className="flex-1 bg-transparent text-sm text-slate-100 placeholder-slate-600 focus:outline-none"
          />
          {dictation.supported && (
            <button
              type="button"
              onClick={dictation.toggle}
              title="Dictate"
              className={`shrink-0 text-xs px-2 py-1 rounded-md transition-colors ${
                dictation.listening ? 'bg-rose-500/20 text-rose-300 ring-1 ring-rose-500/40' : 'text-slate-500 hover:text-white'
              }`}
            >
              <span className={dictation.listening ? 'animate-pulse' : ''}>🎙</span>
            </button>
          )}
          <kbd className="hidden sm:block text-[10px] text-slate-600 border border-white/10 rounded px-1.5 py-0.5">esc</kbd>
        </div>

        {answer ? (
          <div className="p-5">
            <p className="text-sm text-slate-200 leading-relaxed">{answer}</p>
            <button onClick={() => setAnswer('')} className="mt-3 text-xs text-indigo-400 hover:underline">
              ← ask something else
            </button>
          </div>
        ) : (
          <ul className="max-h-80 overflow-y-auto py-2">
            {items.length === 0 ? (
              <li className="px-4 py-6 text-center text-sm text-slate-500">
                No match. Try “high risk over $500”, “reports”, or “are we winning?”
              </li>
            ) : items.map((it, i) => {
              const k = KIND[it.kind];
              return (
                <li key={i}>
                  <button
                    onMouseEnter={() => setSel(i)}
                    onClick={() => it.run()}
                    className={`w-full flex items-center gap-3 px-4 py-2.5 text-left transition-colors ${
                      i === sel ? 'bg-white/[0.06]' : ''
                    }`}
                  >
                    <span className="shrink-0 grid place-items-center w-6 h-6 rounded-md text-xs"
                          style={{ color: k.c, background: `${k.c}1a` }}>{k.icon}</span>
                    <span className="flex-1 text-sm text-slate-200 truncate">{it.label}</span>
                    <span className="shrink-0 text-[11px] text-slate-500">{it.hint}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}
