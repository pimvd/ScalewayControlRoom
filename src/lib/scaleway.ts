import type { Key, LogLine, MonthlyCosts, ScalewayClient, Server, ServerAction, ServerlessApp, Zone } from './types';

const BASE = 'https://api.scaleway.com';
const PAGE_SIZE = 100;
const LOG_LIMIT = 200;

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

export function isForbidden(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403);
}

// Scaleway sends money as google.type.Money: whole units plus nanos.
export function money(value: { units?: number | string; nanos?: number } | undefined): number {
  return Number(value?.units ?? 0) + (value?.nanos ?? 0) / 1e9;
}

// Every Scaleway endpoint, Cockpit's Loki included, takes the IAM secret key.
async function request<T>(secretKey: string, url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
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

interface ApiNamespace {
  id: string;
  project_id: string;
}

interface ApiServerless {
  id: string;
  name: string;
  status: string;
  namespace_id: string;
  min_scale: number;
  max_scale: number;
  public_endpoint?: string; // containers
  domain_name?: string; // functions
}

interface LokiResponse {
  data: { result: { values: [string, string][] }[] };
}

// Loki's streams merged into one list, newest first.
export function toLogLines(responses: LokiResponse[], limit = LOG_LIMIT): LogLine[] {
  return responses
    .flatMap((r) => r.data.result.flatMap((stream) => stream.values))
    .map(([ns, line]) => ({ time: Number(ns.slice(0, -6)), line }))
    .sort((a, b) => b.time - a.time)
    .slice(0, limit);
}

export function scalewayClient(key: Key): ScalewayClient {
  const get = <T>(path: string) => request<T>(key.secretKey, BASE + path);
  const pages = <T>(path: string, field: string) =>
    allPages((page) =>
      get<Record<string, T[]>>(`${path}${path.includes('?') ? '&' : '?'}page_size=${PAGE_SIZE}&page=${page}`).then(
        (r) => r[field],
      ),
    );

  return {
    async listServers(zone) {
      // The Instance API pages with per_page rather than page_size.
      const servers = await allPages((page) =>
        get<{ servers: ApiServer[] }>(`/instance/v1/zones/${zone}/servers?per_page=${PAGE_SIZE}&page=${page}`).then(
          (r) => r.servers,
        ),
      );
      return servers.map(toServer);
    },

    async act(zone, serverId, action: ServerAction) {
      await request(key.secretKey, `${BASE}/instance/v1/zones/${zone}/servers/${serverId}/action`, {
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

    async listServerless(region) {
      // Containers and functions carry no project; their namespace does.
      // Functions can only be listed per namespace.
      const [containerNamespaces, containers, functionNamespaces] = await Promise.all([
        pages<ApiNamespace>(`/containers/v1/regions/${region}/namespaces`, 'namespaces'),
        pages<ApiServerless>(`/containers/v1/regions/${region}/containers`, 'containers'),
        pages<ApiNamespace>(`/functions/v1beta1/regions/${region}/namespaces`, 'namespaces'),
      ]);
      const functions = (
        await Promise.all(
          functionNamespaces.map((ns) =>
            pages<ApiServerless>(`/functions/v1beta1/regions/${region}/functions?namespace_id=${ns.id}`, 'functions'),
          ),
        )
      ).flat();
      const projectOf = new Map([...containerNamespaces, ...functionNamespaces].map((ns) => [ns.id, ns.project_id]));
      const toApp = (a: ApiServerless, kind: ServerlessApp['kind']): ServerlessApp => ({
        id: a.id,
        name: a.name,
        kind,
        status: a.status,
        region,
        projectId: projectOf.get(a.namespace_id) ?? '',
        endpoint: a.public_endpoint || a.domain_name || '',
        minScale: a.min_scale,
        maxScale: a.max_scale,
      });
      return [...containers.map((a) => toApp(a, 'container')), ...functions.map((a) => toApp(a, 'function'))];
    },

    async logs(target, minutes) {
      // Scaleway's own logs and any custom log data source the user pushes to.
      const sources = await pages<{ url: string }>(
        `/cockpit/v1/regions/${target.region}/data-sources?project_id=${target.projectId}&types=logs`,
        'data_sources',
      );
      const now = Date.now();
      const query = encodeURIComponent(`{resource_id="${target.resourceId}"}`);
      const range = `start=${now - minutes * 60_000}000000&end=${now}000000`;
      const responses = await Promise.all(
        sources.map((s) =>
          request<LokiResponse>(
            key.secretKey,
            `${s.url}/loki/api/v1/query_range?query=${query}&${range}&limit=${LOG_LIMIT}&direction=backward`,
          ),
        ),
      );
      return { sources: sources.length, lines: toLogLines(responses) };
    },
  };
}
