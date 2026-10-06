export function SparkBars({
  points,
  label,
  empty,
}: {
  points: { key: string; value: number }[];
  label: string;
  empty: string;
}) {
  if (points.length === 0) {
    return <p className="text-sm text-muted">{empty}</p>;
  }
  const max = Math.max(...points.map((point) => point.value), 1);
  return (
    <div>
      <p className="sr-only">{label}</p>
      <div className="flex h-24 items-end gap-1" role="img" aria-label={label}>
        {points.map((point) => (
          <div key={point.key} className="flex min-w-0 flex-1 flex-col items-center justify-end">
            <div className="w-full rounded-sm bg-accent/70" style={{ height: `${Math.max(6, (point.value / max) * 100)}%` }} title={`${point.key}: ${point.value}`} />
          </div>
        ))}
      </div>
      <p className="mt-2 text-xs text-muted">{label}</p>
    </div>
  );
}
