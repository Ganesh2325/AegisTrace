export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms)) return "No data";
  const sign = ms < 0 ? "-" : "";
  const abs = Math.abs(ms);
  if (abs < 1000) return `${sign}${Math.round(abs)} ms`;
  const totalSeconds = abs / 1000;
  if (abs < 60_000) {
    const seconds = trimTenths(totalSeconds);
    return seconds === "60.0" ? `${sign}1m` : `${sign}${strip(seconds)}s`;
  }
  let minutes = Math.floor(totalSeconds / 60);
  let seconds = trimTenths(totalSeconds - minutes * 60);
  if (seconds === "60.0") {
    minutes += 1;
    seconds = "0.0";
  }
  if (seconds === "0.0") return `${sign}${minutes}m`;
  return `${sign}${minutes}m ${strip(seconds)}s`;
}

export function formatExactMs(ms: number): string {
  return `${Math.round(ms).toLocaleString("en-US")} ms`;
}

function trimTenths(seconds: number): string {
  return seconds.toFixed(1);
}

function strip(seconds: string): string {
  return seconds.endsWith(".0") ? seconds.slice(0, -2) : seconds;
}
