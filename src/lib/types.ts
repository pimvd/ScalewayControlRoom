// Every zone the Instance API serves (from its OpenAPI spec). Listing runs
// against all of them, because a key cannot tell us where its servers are.
export const ZONES = [
  'fr-par-1',
  'fr-par-2',
  'fr-par-3',
  'nl-ams-1',
  'nl-ams-2',
  'nl-ams-3',
  'pl-waw-1',
  'pl-waw-2',
  'pl-waw-3',
  'it-mil-1',
] as const;

export type Zone = (typeof ZONES)[number];

export type ServerState = 'running' | 'stopped' | 'stopped in place' | 'starting' | 'stopping' | 'locked';

// poweroff stops the server and releases its hypervisor, which is what stops
// the compute billing. stop_in_place keeps it reserved (and billed), so the
// app does not offer it.
export type ServerAction = 'poweron' | 'poweroff' | 'reboot';

export interface Server {
  id: string;
  name: string;
  state: ServerState;
  commercialType: string;
  zone: Zone;
  project: string;
  tags: string[];
  publicIps: string[];
  createdAt?: string;
}

export interface Consumption {
  amount: number;
  category: string;
  product: string;
  project: string;
}

export interface MonthlyCosts {
  items: Consumption[];
  discount: number;
  currency: string;
}

// A Scaleway API key as the user entered it. Only the secret key is sent to
// the API, so the access key is not asked for. The demo key is a key like any
// other, served by a fake client instead of the API.
export interface Key {
  id: string;
  label: string;
  secretKey: string;
  organizationId: string;
  demo?: boolean;
}

export interface ScalewayClient {
  listServers(zone: Zone): Promise<Server[]>;
  act(zone: Zone, serverId: string, action: ServerAction): Promise<void>;
  // period is YYYY-MM
  costs(period: string): Promise<MonthlyCosts>;
  // commercial type -> price per hour
  hourlyPrices(zone: Zone): Promise<Record<string, number>>;
}
