import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';

import { demoClient } from './demo';
import { loadKeys, saveKeys } from './keys';
import { isBusy, mergeById, sortServers, type Listed } from './logic';
import { isForbidden, scalewayClient } from './scaleway';
import {
  REGIONS,
  ZONES,
  type Key,
  type ScalewayClient,
  type Server,
  type ServerAction,
  type ServerlessApp,
} from './types';

export function clientFor(key: Key): ScalewayClient {
  return key.demo ? demoClient : scalewayClient(key);
}

// Runs `call` with the first of `keyIds` that is allowed to: a read-only key
// next to a full one for the same resource is not a reason to fail.
export async function withKeys<T>(
  keys: Key[],
  keyIds: string[],
  call: (client: ScalewayClient) => Promise<T>,
): Promise<T> {
  let lastError: unknown = new Error('No key can reach this resource.');
  for (const keyId of keyIds) {
    const key = keys.find((k) => k.id === keyId);
    if (!key) continue;
    try {
      return await call(clientFor(key));
    } catch (error) {
      lastError = error;
      if (!isForbidden(error)) break;
    }
  }
  throw lastError;
}

export interface KeyProblem {
  keyId: string;
  area: 'instances' | 'serverless';
  message: string;
}

interface Store {
  keys: Key[];
  loaded: boolean;
  servers: Listed<Server>[];
  serverless: Listed<ServerlessApp>[];
  problems: KeyProblem[];
  // `${zone}/${commercialType}` -> price per hour
  prices: Record<string, number>;
  refreshing: boolean;
  refresh(): Promise<void>;
  addKey(key: Key): Promise<void>;
  removeKey(id: string): Promise<void>;
  act(listed: Listed<Server>, action: ServerAction): Promise<void>;
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

// Lists `places` in parallel. Places that fail are named in the problem, unless
// the key simply may not see this kind of resource: then it is left out quietly.
async function listAll<P extends string, T>(places: readonly P[], list: (place: P) => Promise<T[]>, quietForbidden: boolean) {
  const results = await Promise.allSettled(places.map(list));
  const items = results.flatMap((r) => (r.status === 'fulfilled' ? r.value : []));
  const failed = places.filter((_, i) => {
    const r = results[i];
    return r.status === 'rejected' && !(quietForbidden && isForbidden(r.reason));
  });
  if (!failed.length) return { items };
  const reason = (results[places.indexOf(failed[0])] as PromiseRejectedResult).reason;
  const problem = failed.length === places.length ? message(reason) : `Could not load ${failed.join(', ')}: ${message(reason)}`;
  return { items, problem };
}

export function StoreProvider({ children }: { children: ReactNode }) {
  const [keys, setKeys] = useState<Key[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [servers, setServers] = useState<Listed<Server>[]>([]);
  const [serverless, setServerless] = useState<Listed<ServerlessApp>[]>([]);
  const [problems, setProblems] = useState<KeyProblem[]>([]);
  const [prices, setPrices] = useState<Record<string, number>>({});
  const [refreshing, setRefreshing] = useState(false);
  const busy = useRef(false);
  const pricedZones = useRef(new Set<string>());

  const loadPrices = useCallback((current: Key[], listed: Listed<Server>[]) => {
    // Prices never change while the app runs: fetch each zone once.
    for (const { item, keyIds } of listed) {
      if (pricedZones.current.has(item.zone)) continue;
      pricedZones.current.add(item.zone);
      withKeys(current, keyIds, (client) => client.hourlyPrices(item.zone))
        .then((zonePrices) =>
          setPrices((p) => ({
            ...p,
            ...Object.fromEntries(Object.entries(zonePrices).map(([type, price]) => [`${item.zone}/${type}`, price])),
          })),
        )
        .catch(() => pricedZones.current.delete(item.zone));
    }
  }, []);

  const refreshWith = useCallback(
    async (current: Key[]) => {
      if (busy.current) return;
      busy.current = true;
      setRefreshing(true);
      try {
        const results = await Promise.all(
          current.map(async (key) => {
            const client = clientFor(key);
            const [instances, apps] = await Promise.all([
              listAll(ZONES, (zone) => client.listServers(zone), false),
              listAll(REGIONS, (region) => client.listServerless(region), true),
            ]);
            return { key, instances, apps };
          }),
        );
        const listedServers = sortServers(mergeById(results.map((r) => ({ keyId: r.key.id, items: r.instances.items }))));
        setServers(listedServers);
        setServerless(
          mergeById(results.map((r) => ({ keyId: r.key.id, items: r.apps.items }))).sort((a, b) =>
            a.item.name.localeCompare(b.item.name),
          ),
        );
        setProblems(
          results.flatMap((r) => [
            ...(r.instances.problem ? [{ keyId: r.key.id, area: 'instances' as const, message: r.instances.problem }] : []),
            ...(r.apps.problem ? [{ keyId: r.key.id, area: 'serverless' as const, message: r.apps.problem }] : []),
          ]),
        );
        loadPrices(current, listedServers);
      } finally {
        busy.current = false;
        setRefreshing(false);
      }
    },
    [loadPrices],
  );

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
    if (!servers.some((l) => isBusy(l.item.state))) return;
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
    serverless,
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
      await withKeys(keys, listed.keyIds, (client) => client.act(listed.item.zone, listed.item.id, action));
      if (action !== 'reboot') {
        const state = action === 'poweroff' ? 'stopping' : 'starting';
        setServers((all) => all.map((l) => (l.item.id === listed.item.id ? { ...l, item: { ...l.item, state } } : l)));
      }
      await refreshWith(keys);
    },
  };

  return <StoreContext.Provider value={store}>{children}</StoreContext.Provider>;
}
