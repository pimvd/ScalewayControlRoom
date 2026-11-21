// Every Pro feature asks this one hook. Until the subscription ships, all of
// them are unlocked; flipping this to the real entitlement is the only change
// the screens need.
export function usePro(): boolean {
  return true;
}

// Without Pro, one real key (the demo key does not count).
export const FREE_KEYS = 1;

// The subscriptions the app sells. AppStore/subscriptions.json configures the
// same IDs in App Store Connect; scripts/validate_subscriptions.py checks both
// lists match.
export const PRO_PRODUCT_IDS = ['com.pvd.scwcontrolroom.pro.monthly', 'com.pvd.scwcontrolroom.pro.yearly'];
