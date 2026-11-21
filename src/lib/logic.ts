// Pure functions behind the screens, kept free of React so they can be tested.
import type { Consumption, Key, Server } from './types';

// A resource as the app lists it, with the keys that can see it. Two keys
// for the same organization see the same resources; each is shown once.
export interface Listed<T = Server> {
  item: T;
  keyIds: string[];
}

export function mergeById<T extends { id: string }>(results: { keyId: string; items: T[] }[]): Listed<T>[] {
  const byId = new Map<string, Listed<T>>();
  for (const { keyId, items } of results) {
    for (const item of items) {
      const listed = byId.get(item.id);
      if (listed) listed.keyIds.push(keyId);
      else byId.set(item.id, { item, keyIds: [keyId] });
    }
  }
  return [...byId.values()];
}

// What is costing money comes first.
const STATE_ORDER: Record<Server['state'], number> = {
  running: 0,
  starting: 1,
  stopping: 2,
  locked: 3,
  'stopped in place': 4,
  stopped: 5,
};

export function sortServers(list: Listed[]): Listed[] {
  return [...list].sort(
    (a, b) =>
      STATE_ORDER[a.item.state] - STATE_ORDER[b.item.state] || a.item.name.localeCompare(b.item.name),
  );
}

export function isBusy(state: Server['state']): boolean {
  return state === 'starting' || state === 'stopping';
}

export type StateFilter = 'all' | 'running' | 'stopped';

export function filterServers(list: Listed[], query: string, state: StateFilter): Listed[] {
  const q = query.trim().toLowerCase();
  return list.filter(({ item: server }) => {
    if (state === 'running' && server.state === 'stopped') return false;
    if (state === 'stopped' && server.state !== 'stopped') return false;
    if (!q) return true;
    return server.name.toLowerCase().includes(q) || server.tags.some((t) => t.toLowerCase().includes(q));
  });
}

// Billing is per organization, so several keys for one organization must
// not count its costs more than once.
export function uniqueOrganizations(keys: Key[]): Key[] {
  const seen = new Set<string>();
  return keys.filter((k) => !seen.has(k.organizationId) && seen.add(k.organizationId));
}

export function sumBy(items: Consumption[], field: 'category' | 'project'): [string, number][] {
  const totals = new Map<string, number>();
  for (const item of items) totals.set(item[field], (totals.get(item[field]) ?? 0) + item.amount);
  return [...totals.entries()].sort((a, b) => b[1] - a[1]);
}

export function total(items: Consumption[], discount: number): number {
  return items.reduce((sum, item) => sum + item.amount, 0) - discount;
}

// YYYY-MM of this month and the months before it, newest first.
export function recentPeriods(now: Date, count: number): string[] {
  return Array.from({ length: count }, (_, i) => {
    const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - i, 1));
    return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
  });
}

export function formatMoney(amount: number, currency = 'EUR'): string {
  return new Intl.NumberFormat('en-IE', { style: 'currency', currency }).format(amount);
}

export function formatPeriod(period: string): string {
  const [year, month] = period.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, 1)).toLocaleDateString('en-GB', {
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  });
}
