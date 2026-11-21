import type { Key, MonthlyCosts, ScalewayClient, Server, ServerAction } from './types';

// The demo key lets App Review, screenshots and curious users try the app
// without a Scaleway account. Everything here is made up.
export const DEMO_KEY: Key = {
  id: 'demo',
  label: 'Demo',
  secretKey: '',
  organizationId: 'demo-organization',
  demo: true,
};

const SETTLE_MS = 3000;

function server(
  id: string,
  name: string,
  state: Server['state'],
  commercialType: string,
  zone: Server['zone'],
  project: string,
  tags: string[],
): Server {
  return { id, name, state, commercialType, zone, project, tags, publicIps: [], createdAt: '2026-03-02T09:00:00Z' };
}

function initialServers(): Server[] {
  return [
    server('demo-1', 'web-prod-1', 'running', 'PRO2-S', 'fr-par-1', 'webshop', ['prod']),
    server('demo-2', 'web-prod-2', 'running', 'PRO2-S', 'fr-par-2', 'webshop', ['prod']),
    server('demo-3', 'staging', 'running', 'DEV1-M', 'fr-par-1', 'webshop', ['dev']),
    server('demo-4', 'ci-runner', 'running', 'GP1-XS', 'nl-ams-1', 'tooling', ['dev']),
    server('demo-5', 'gpu-training', 'stopped', 'L4-1-24G', 'fr-par-2', 'ml', ['dev']),
    server('demo-6', 'old-wordpress', 'stopped', 'DEV1-S', 'nl-ams-1', 'tooling', []),
  ];
}

export const DEMO_PRICES: Record<string, number> = {
  'DEV1-S': 0.0109,
  'DEV1-M': 0.0219,
  'GP1-XS': 0.091,
  'PRO2-S': 0.219,
  'L4-1-24G': 0.75,
};

const DEMO_COSTS: [category: string, product: string, project: string, amount: number][] = [
  ['Compute', 'Instances PRO2-S', 'webshop', 112.4],
  ['Compute', 'Instances DEV1-M', 'webshop', 11.8],
  ['Compute', 'Instances GP1-XS', 'tooling', 49.1],
  ['Compute', 'Instances L4-1-24G', 'ml', 36.0],
  ['Storage', 'Block Storage', 'webshop', 8.6],
  ['Storage', 'Object Storage', 'tooling', 2.3],
  ['Network', 'Flexible IP', 'webshop', 2.9],
  ['Observability', 'Cockpit', 'webshop', 1.2],
];

function wait(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

let servers = initialServers();

// For tests: start from the initial demo state again.
export function resetDemo() {
  servers = initialServers();
}

function setState(id: string, state: Server['state']) {
  servers = servers.map((s) => (s.id === id ? { ...s, state } : s));
}

export const demoClient: ScalewayClient = {
  async listServers(zone) {
    await wait(300);
    return servers.filter((s) => s.zone === zone);
  },

  async act(_zone, serverId, action: ServerAction) {
    await wait(300);
    const busy = action === 'poweroff' ? 'stopping' : 'starting';
    const done = action === 'poweroff' ? 'stopped' : 'running';
    setState(serverId, busy);
    setTimeout(() => setState(serverId, done), SETTLE_MS);
  },

  async costs(period): Promise<MonthlyCosts> {
    await wait(300);
    // Older months a little different, so the history does not look copied.
    const factor = 1 - (Number(period.slice(5)) % 3) * 0.08;
    return {
      items: DEMO_COSTS.map(([category, product, project, amount]) => ({
        category,
        product,
        project,
        amount: Math.round(amount * factor * 100) / 100,
      })),
      discount: 0,
      currency: 'EUR',
    };
  },

  async hourlyPrices() {
    return DEMO_PRICES;
  },
};
