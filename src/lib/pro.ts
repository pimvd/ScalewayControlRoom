// Every Pro feature asks this one hook. Until the subscription ships, all of
// them are unlocked; flipping this to the real entitlement is the only change
// the screens need.
export function usePro(): boolean {
  return true;
}

// Without Pro, one real key (the demo key does not count).
export const FREE_KEYS = 1;
