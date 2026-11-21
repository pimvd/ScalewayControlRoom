import * as SecureStore from 'expo-secure-store';

import { DEMO_KEY } from './demo';
import type { Key } from './types';

// Everything lives in the keychain: the list of keys without their secrets
// under one entry, and each secret under its own, so no entry grows large.
const INDEX = 'keys';
const secretName = (id: string) => `secret.${id}`;

type StoredKey = Omit<Key, 'secretKey'>;

export async function loadKeys(): Promise<Key[]> {
  const raw = await SecureStore.getItemAsync(INDEX);
  if (!raw) return [];
  const stored: StoredKey[] = JSON.parse(raw);
  return Promise.all(
    stored.map(async (k) =>
      k.demo ? DEMO_KEY : { ...k, secretKey: (await SecureStore.getItemAsync(secretName(k.id))) ?? '' },
    ),
  );
}

export async function saveKeys(keys: Key[], removed: Key[] = []): Promise<void> {
  for (const key of keys) {
    if (!key.demo) await SecureStore.setItemAsync(secretName(key.id), key.secretKey);
  }
  for (const key of removed) await SecureStore.deleteItemAsync(secretName(key.id));
  const index: StoredKey[] = keys.map(({ secretKey: _secret, ...rest }) => rest);
  await SecureStore.setItemAsync(INDEX, JSON.stringify(index));
}

export function newKeyId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Returns what is wrong with the entered key, or null when it looks right.
export function validateKey(input: Pick<Key, 'label' | 'secretKey' | 'organizationId'>): string | null {
  if (!input.label.trim()) return 'Give the key a name.';
  if (!UUID.test(input.secretKey.trim())) return 'The secret key is a UUID, like 1a2b3c4d-....';
  if (!UUID.test(input.organizationId.trim())) return 'The organization ID is a UUID, like 1a2b3c4d-....';
  return null;
}
