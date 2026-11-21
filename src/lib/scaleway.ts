import type { Key, MonthlyCosts, ScalewayClient, Server, ServerAction, Zone } from './types';

const BASE = 'https://api.scaleway.com';
const PAGE_SIZE = 100;

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

// Scaleway sends money as google.type.Money: whole units plus nanos.
export function money(value: { units?: number | string; nanos?: number } | undefined): number {
  return Number(value?.units ?? 0) + (value?.nanos ?? 0) / 1e9;
}

async function request<T>(secretKey: string, path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(BASE + path, {
    ...init,
    headers: { 'X-Auth-Token': secretKey, 'Content-Type': 'application/json' },
  });
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const body = await response.json();
      if (body?.message) message = body.message;
    } catch {
      // Keep the status line.
    }
    throw new ApiError(response.status, message);
  }
  return response.json();
}

// Fetches every page of a list endpoint: a short page is the last one.
async function allPages<T>(fetchPage: (page: number) => Promise<T[]>): Promise<T[]> {
  const all: T[] = [];
  for (let page = 1; ; page++) {
    const items = await fetchPage(page);
    all.push(...items);
    if (items.length < PAGE_SIZE) return all;
  }
}

interface ApiServer {
  id: string;
  name: string;
  state: Server['state'];
  commercial_type: string;
  zone: Zone;
  project: string;
  tags?: string[];
  public_ips?: { address: string }[];
  creation_date?: string | null;
}

export function toServer(s: ApiServer): Server {
  return {
    id: s.id,
    name: s.name,
    state: s.state,
    commercialType: s.commercial_type,
    zone: s.zone,
    project: s.project,
    tags: s.tags ?? [],
    publicIps: (s.public_ips ?? []).map((ip) => ip.address),
    createdAt: s.creation_date ?? undefined,
  };
}

interface ApiConsumption {
  value: { units?: number | string; nanos?: number; currency_code?: string };
  category_name: string;
  product_name: string;
  project_name?: string;
  project_id: string;
}

export function scalewayClient(key: Key): ScalewayClient {
  const get = <T>(path: string) => request<T>(key.secretKey, path);
  return {
    async listServers(zone) {
      const servers = await allPages((page) =>
        get<{ servers: ApiServer[] }>(
          `/instance/v1/zones/${zone}/servers?per_page=${PAGE_SIZE}&page=${page}`,
        ).then((r) => r.servers),
      );
      return servers.map(toServer);
    },

    async act(zone, serverId, action: ServerAction) {
      await request(key.secretKey, `/instance/v1/zones/${zone}/servers/${serverId}/action`, {
        method: 'POST',
        body: JSON.stringify({ action }),
      });
    },

    async costs(period): Promise<MonthlyCosts> {
      let discount = 0;
      const items = await allPages(async (page) => {
        const r = await get<{ consumptions: ApiConsumption[]; total_discount_untaxed_value?: number }>(
          `/billing/v2beta1/consumptions?organization_id=${key.organizationId}` +
            `&billing_period=${period}&page_size=${PAGE_SIZE}&page=${page}`,
        );
        discount = Math.abs(r.total_discount_untaxed_value ?? discount);
        return r.consumptions;
      });
      return {
        items: items.map((c) => ({
          amount: money(c.value),
          category: c.category_name,
          product: c.product_name,
          project: c.project_name || c.project_id,
        })),
        discount,
        currency: items[0]?.value.currency_code ?? 'EUR',
      };
    },

    async hourlyPrices(zone) {
      // The catalogue is a map keyed by commercial type, paged like a list.
      const entries = await allPages((page) =>
        get<{ servers: Record<string, { hourly_price: number }> }>(
          `/instance/v1/zones/${zone}/products/servers?per_page=${PAGE_SIZE}&page=${page}`,
        ).then((r) => Object.entries(r.servers)),
      );
      return Object.fromEntries(entries.map(([type, t]) => [type, t.hourly_price]));
    },
  };
}
