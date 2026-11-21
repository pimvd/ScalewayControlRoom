import * as IAP from 'expo-iap';
import type { ProductSubscription, Purchase } from 'expo-iap';
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';

import { useStore } from './store';

// The subscriptions the app sells. AppStore/subscriptions.json configures the
// same IDs in App Store Connect; scripts/validate_subscriptions.py checks both
// lists match.
export const PRO_PRODUCTS = [
  { id: 'com.pvd.scwcontrolroom.pro.yearly', per: 'year' },
  { id: 'com.pvd.scwcontrolroom.pro.monthly', per: 'month' },
] as const;

const PRO_PRODUCT_IDS = PRO_PRODUCTS.map((p) => p.id);

// Without Pro, one real key (the demo key does not count).
export const FREE_KEYS = 1;

interface Pro {
  // Pro features are available: subscribed, or only looking at the demo.
  isPro: boolean;
  // An active subscription, checked by StoreKit on the device.
  subscribed: boolean;
  products: ProductSubscription[];
  loading: boolean;
  // The product being bought, or 'restore'.
  busy: string | null;
  error: string | null;
  buy(productId: string): Promise<void>;
  restore(): Promise<void>;
  manage(): Promise<void>;
}

const ProContext = createContext<Pro | null>(null);

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function ProProvider({ children }: { children: ReactNode }) {
  const { keys } = useStore();
  const [subscribed, setSubscribed] = useState(false);
  const [products, setProducts] = useState<ProductSubscription[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const check = useCallback(async () => {
    setSubscribed(await IAP.hasActiveSubscriptions(PRO_PRODUCT_IDS));
  }, []);

  useEffect(() => {
    const updated = IAP.purchaseUpdatedListener(async (purchase: Purchase) => {
      // StoreKit has verified the transaction on the device; there is no
      // server to tell, so finish it and read the entitlement again.
      await IAP.finishTransaction({ purchase, isConsumable: false });
      await check();
      setBusy(null);
    });
    const failed = IAP.purchaseErrorListener((e) => {
      if (!IAP.isUserCancelledError(e)) setError(e.message);
      setBusy(null);
    });
    (async () => {
      try {
        await IAP.initConnection();
        await check();
        const found = (await IAP.fetchProducts({ skus: PRO_PRODUCT_IDS, type: 'subs' })) ?? [];
        // In the order of PRO_PRODUCTS.
        setProducts(
          PRO_PRODUCT_IDS.flatMap((id) => (found as ProductSubscription[]).filter((p) => p.id === id)),
        );
      } catch (e) {
        setError(message(e));
      } finally {
        setLoading(false);
      }
    })();
    return () => {
      updated.remove();
      failed.remove();
      IAP.endConnection().catch(() => {});
    };
  }, [check]);

  const onlyDemo = keys.length > 0 && keys.every((k) => k.demo);

  const pro: Pro = {
    isPro: subscribed || onlyDemo,
    subscribed,
    products,
    loading,
    busy,
    error,
    async buy(productId) {
      setError(null);
      setBusy(productId);
      try {
        // The outcome arrives through the listeners above.
        await IAP.requestPurchase({ request: { apple: { sku: productId } }, type: 'subs' });
      } catch (e) {
        setError(message(e));
        setBusy(null);
      }
    },
    async restore() {
      setError(null);
      setBusy('restore');
      try {
        await IAP.restorePurchases();
        await check();
      } catch (e) {
        setError(message(e));
      } finally {
        setBusy(null);
      }
    },
    async manage() {
      await IAP.deepLinkToSubscriptions({});
    },
  };

  return <ProContext.Provider value={pro}>{children}</ProContext.Provider>;
}

export function useProStore(): Pro {
  const pro = useContext(ProContext);
  if (!pro) throw new Error('useProStore outside ProProvider');
  return pro;
}

// Whether Pro features are available. Every Pro gate asks this.
export function usePro(): boolean {
  return useProStore().isPro;
}
