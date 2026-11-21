import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';

import { demoClient } from './demo';
import { loadKeys, saveKeys } from './keys';
import { isBusy, mergeServers, type Listed } from './logic';
import { ApiError, scalewayClient } from './scaleway';
import { ZONES, type Key, type ScalewayClient, type ServerAction, type Zone } from './types';

export function clientFor(key: Key): ScalewayClient {
  return key.demo ? demoClient : scalewayClient(key);
}

export interface KeyProblem {
  keyId: string;
  message: string;
}

interface Store {
  keys: Key[];
  loaded: boolean;
  servers: Listed[];
  problems: KeyProblem[];
  // `${zone}/${commercialType}` -> price per hour
  prices: Record<string, number>;
  refreshing: boolean;
  refresh(): Promise<void>;
  addKey(key: Key): Promise<void>;
  removeKey(id: string): Promise<void>;
  act(listed: Listed, action: ServerAction): Promise<void>;
}

const StoreContext = createContext<Store | null>(null);

const POLL_MS = 4000;

export function useStore(): Store {
  const store = useContext(StoreContext);
  if (!store) throw new Error('useStore outside StoreProvider');
  return store;
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

async function listForKey(key: Key) {
  const client = clientFor(key);
  const zones = await Promise.allSettled(ZONES.map((zone) => client.listServers(zone)));
  const servers = zones.flatMap((r) => (r.status === 'fulfilled' ? r.value : []));
  const failed = ZONES.filter((_, i) => zones[i].status === 'rejected');
  const firstError = zones.find((r) => r.status === 'rejected') as PromiseRejectedResult | undefined;
  let problem: string | undefined;
  if (firstError) {
    problem =
      failed.length === ZONES.length
        ? message(firstError.reason)
        : `Could not load ${failed.join(', ')}: ${message(firstError.reason)}`;
  }
  return { keyId: key.id, servers, problem };
}

export function StoreProvider({ children }: { children: ReactNode }) {
  const [keys, setKeys] = useState<Key[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [servers, setServers] = useState<Listed[]>([]);
  const [problems, setProblems] = useState<KeyProblem[]>([]);
  const [prices, setPrices] = useState<Record<string, number>>({});
  const [refreshing, setRefreshing] = useState(false);
  const busy = useRef(false);
  const pricedZones = useRef(new Set<string>());

  const refreshWith = useCallback(async (current: Key[]) => {
    if (busy.current) return;
    busy.current = true;
    setRefreshing(true);
    try {
      const results = await Promise.all(current.map(listForKey));
      const listed = mergeServers(results);
      setServers(listed);
      setProblems(results.flatMap((r) => (r.problem ? [{ keyId: r.keyId, message: r.problem }] : [])));
      // Prices never change while the app runs: fetch each zone once.
      for (const { server, keyIds } of listed) {
        const zoneKey = `${keyIds[0]}:${server.zone}`;
        if (pricedZones.current.has(zoneKey)) continue;
        pricedZones.current.add(zoneKey);
        const key = current.find((k) => k.id === keyIds[0])!;
        clientFor(key)
          .hourlyPrices(server.zone as Zone)
          .then((zonePrices) =>
            setPrices((p) => ({
              ...p,
              ...Object.fromEntries(Object.entries(zonePrices).map(([type, price]) => [`${server.zone}/${type}`, price])),
            })),
          )
          .catch(() => pricedZones.current.delete(zoneKey));
      }
    } finally {
      busy.current = false;
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    loadKeys()
      .then((stored) => {
        setKeys(stored);
        return refreshWith(stored);
      })
      .finally(() => setLoaded(true));
  }, [refreshWith]);

  // While a server is starting or stopping, keep checking until it settles.
  useEffect(() => {
    if (!servers.some((l) => isBusy(l.server.state))) return;
    const timer = setTimeout(() => refreshWith(keys), POLL_MS);
    return () => clearTimeout(timer);
  }, [servers, keys, refreshWith]);

  const updateKeys = useCallback(
    async (next: Key[], removed: Key[] = []) => {
      await saveKeys(next, removed);
      setKeys(next);
      await refreshWith(next);
    },
    [refreshWith],
  );

  const store: Store = {
    keys,
    loaded,
    servers,
    problems,
    prices,
    refreshing,
    refresh: () => refreshWith(keys),
    addKey: (key) => updateKeys([...keys.filter((k) => k.id !== key.id), key]),
    removeKey: (id) =>
      updateKeys(
        keys.filter((k) => k.id !== id),
        keys.filter((k) => k.id === id),
      ),
    async act(listed, action) {
      // Use the first key that is allowed to: a read-only key next to a full
      // one for the same server is not a reason to fail.
      let lastError: unknown;
      for (const keyId of listed.keyIds) {
        const key = keys.find((k) => k.id === keyId);
        if (!key) continue;
        try {
          await clientFor(key).act(listed.server.zone, listed.server.id, action);
          lastError = undefined;
          break;
        } catch (error) {
          lastError = error;
          if (!(error instanceof ApiError && (error.status === 401 || error.status === 403))) break;
        }
      }
      if (lastError) throw lastError;
      if (action !== 'reboot') {
        const state = action === 'poweroff' ? 'stopping' : 'starting';
        setServers((all) =>
          all.map((l) => (l.server.id === listed.server.id ? { ...l, server: { ...l.server, state } } : l)),
        );
      }
      await refreshWith(keys);
    },
  };

  return <StoreContext.Provider value={store}>{children}</StoreContext.Provider>;
}
