export function formatUtc(value: string): string {
  return new Intl.DateTimeFormat("en-GB", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
    timeZone: "UTC",
    timeZoneName: "short",
  }).format(new Date(value));
}

export function formatDuration(start: string, end: string): string {
  const milliseconds = Math.max(0, Date.parse(end) - Date.parse(start));
  const totalSeconds = Math.floor(milliseconds / 1_000);
  const hours = Math.floor(totalSeconds / 3_600);
  const minutes = Math.floor((totalSeconds % 3_600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m ${seconds}s`;
}

export function formatConfidence(value: number | null): string {
  return value === null ? "Not reported" : value.toFixed(2);
}

export function formatCost(amount: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: amount === 0 ? 2 : 4,
    maximumFractionDigits: 6,
  }).format(amount);
}

export function statusLabel(value: string): string {
  return value.toLowerCase().replaceAll("_", " ").replace(/^\w/u, (character) =>
    character.toUpperCase());
}
