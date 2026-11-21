import { afterEach, beforeEach, describe, expect, it, jest } from '@jest/globals';

import { demoClient, resetDemo } from './demo';
import { validateKey } from './keys';
import {
  filterServers,
  mergeServers,
  recentPeriods,
  sumBy,
  total,
  uniqueOrganizations,
} from './logic';
import { money, toServer } from './scaleway';
import type { Key, Server } from './types';

function server(id: string, name: string, state: Server['state'], tags: string[] = []): Server {
  return { id, name, state, commercialType: 'DEV1-S', zone: 'fr-par-1', project: 'p', tags, publicIps: [] };
}

function key(id: string, organizationId: string): Key {
  return { id, label: id, secretKey: 's', organizationId };
}

describe('mergeServers', () => {
  it('shows a server two keys can see once, with both keys', () => {
    const shared = server('1', 'web', 'running');
    const merged = mergeServers([
      { keyId: 'a', servers: [shared] },
      { keyId: 'b', servers: [shared, server('2', 'db', 'running')] },
    ]);
    expect(merged).toHaveLength(2);
    expect(merged.find((l) => l.server.id === '1')!.keyIds).toEqual(['a', 'b']);
  });

  it('puts running servers first, then by name', () => {
    const merged = mergeServers([
      { keyId: 'a', servers: [server('1', 'b', 'stopped'), server('2', 'z', 'running'), server('3', 'a', 'running')] },
    ]);
    expect(merged.map((l) => l.server.name)).toEqual(['a', 'z', 'b']);
  });
});

describe('filterServers', () => {
  const list = mergeServers([
    { keyId: 'a', servers: [server('1', 'web-prod', 'running', ['prod']), server('2', 'staging', 'stopped', ['dev'])] },
  ]);

  it('filters by state', () => {
    expect(filterServers(list, '', 'running').map((l) => l.server.id)).toEqual(['1']);
    expect(filterServers(list, '', 'stopped').map((l) => l.server.id)).toEqual(['2']);
    expect(filterServers(list, '', 'all')).toHaveLength(2);
  });

  it('searches names and tags', () => {
    expect(filterServers(list, 'WEB', 'all').map((l) => l.server.id)).toEqual(['1']);
    expect(filterServers(list, 'dev', 'all').map((l) => l.server.id)).toEqual(['2']);
  });
});

describe('costs', () => {
  it('counts each organization once', () => {
    const keys = [key('a', 'org1'), key('b', 'org1'), key('c', 'org2')];
    expect(uniqueOrganizations(keys).map((k) => k.id)).toEqual(['a', 'c']);
  });

  it('sums by category, largest first, and subtracts discounts', () => {
    const items = [
      { amount: 1, category: 'Storage', product: 'x', project: 'p' },
      { amount: 5, category: 'Compute', product: 'y', project: 'p' },
      { amount: 2, category: 'Storage', product: 'z', project: 'q' },
    ];
    expect(sumBy(items, 'category')).toEqual([
      ['Compute', 5],
      ['Storage', 3],
    ]);
    expect(total(items, 1.5)).toBe(6.5);
  });

  it('lists this month and the months before it', () => {
    expect(recentPeriods(new Date('2026-02-15T12:00:00Z'), 3)).toEqual(['2026-02', '2026-01', '2025-12']);
  });

  it('reads Scaleway money, whose units may arrive as a string', () => {
    expect(money({ units: '12', nanos: 500_000_000 })).toBe(12.5);
    expect(money(undefined)).toBe(0);
  });
});

describe('toServer', () => {
  it('maps the API shape', () => {
    const s = toServer({
      id: '1',
      name: 'web',
      state: 'running',
      commercial_type: 'DEV1-S',
      zone: 'nl-ams-1',
      project: 'p',
      public_ips: [{ address: '51.15.0.1' }],
      creation_date: null,
    });
    expect(s).toMatchObject({ commercialType: 'DEV1-S', publicIps: ['51.15.0.1'], tags: [], createdAt: undefined });
  });
});

describe('validateKey', () => {
  const uuid = '1a2b3c4d-1a2b-1a2b-1a2b-1a2b3c4d5e6f';
  it('accepts a complete key', () => {
    expect(validateKey({ label: 'Work', secretKey: uuid, organizationId: uuid })).toBeNull();
  });
  it('rejects a secret that is not a UUID', () => {
    expect(validateKey({ label: 'Work', secretKey: 'SCW123', organizationId: uuid })).toMatch(/secret/);
  });
});

describe('demo', () => {
  beforeEach(() => {
    resetDemo();
    jest.useFakeTimers();
  });
  afterEach(() => {
    jest.useRealTimers();
  });

  it('stops a server and settles on stopped', async () => {
    const list = async () => {
      const p = demoClient.listServers('fr-par-1');
      jest.advanceTimersByTime(300);
      return p;
    };
    const act = demoClient.act('fr-par-1', 'demo-1', 'poweroff');
    jest.advanceTimersByTime(300);
    await act;
    expect((await list()).find((s) => s.id === 'demo-1')!.state).toBe('stopping');
    jest.advanceTimersByTime(3000);
    expect((await list()).find((s) => s.id === 'demo-1')!.state).toBe('stopped');
  });
});
