const COLOR = {
  HIGH:   'bg-red-500/20 text-red-400 ring-red-500/30',
  MEDIUM: 'bg-orange-500/20 text-orange-400 ring-orange-500/30',
  LOW:    'bg-yellow-500/20 text-yellow-400 ring-yellow-500/30',
  NORMAL: 'bg-green-500/20 text-green-400 ring-green-500/30',
};

export default function RiskBadge({ riskLevel }) {
  const cls = COLOR[riskLevel] ?? COLOR.NORMAL;
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold ring-1 ${cls}`}>
      {riskLevel}
    </span>
  );
}
