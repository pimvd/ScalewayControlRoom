import { router } from 'expo-router';
import { ActivityIndicator, Linking, Pressable, ScrollView, Text, View } from 'react-native';

import { Banner, Button, Muted, styles, useColors } from '@/components/ui';
import { PRIVACY_URL, TERMS_URL } from '@/lib/links';
import { PRO_PRODUCTS, useProStore } from '@/lib/pro';
import type { ProductSubscription } from 'expo-iap';

const FEATURES = [
  ['Serverless', 'Your containers and functions in every region.'],
  ['Logs', 'Serverless and instance logs from Cockpit, for the last 15 minutes, hour or day.'],
  ['Cost details', 'Costs by category and by project, and the months before.'],
  ['More keys', 'Every organization and project you work on, side by side.'],
];

function freeTrial(product: ProductSubscription): string | null {
  const offer = product.subscriptionOffers?.find((o) => o.paymentMode === 'free-trial');
  if (!offer?.period) return null;
  const count = (offer.periodCount ?? 1) * offer.period.value;
  return `${count} ${offer.period.unit}${count === 1 ? '' : 's'} free, then`;
}

export default function ProScreen() {
  const pro = useProStore();
  const c = useColors();

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Text style={{ color: c.text, fontSize: 28, fontWeight: '700', marginBottom: 4 }}>ControlRoom Pro</Text>
      <Muted style={{ fontSize: 16, marginBottom: 20 }}>
        Free: your instances, starting and stopping them, and this month&apos;s total cost. Pro adds:
      </Muted>

      {FEATURES.map(([title, text]) => (
        <View key={title} style={{ flexDirection: 'row', gap: 10, marginBottom: 14 }}>
          <Text style={{ color: c.tint, fontSize: 17 }}>✓</Text>
          <View style={{ flex: 1 }}>
            <Text style={{ color: c.text, fontSize: 17, fontWeight: '600' }}>{title}</Text>
            <Muted style={{ fontSize: 15 }}>{text}</Muted>
          </View>
        </View>
      ))}

      <View style={{ height: 12 }} />
      {pro.error ? <Banner>{pro.error}</Banner> : null}

      {pro.subscribed ? (
        <>
          <Text style={{ color: c.text, fontSize: 17, marginBottom: 16 }}>You have Pro. Thank you!</Text>
          <Button title="Manage subscription" kind="secondary" onPress={pro.manage} />
          <Button title="Done" onPress={() => router.back()} />
        </>
      ) : pro.loading ? (
        <ActivityIndicator style={{ marginVertical: 24 }} />
      ) : pro.products.length === 0 ? (
        <Muted style={{ marginBottom: 16 }}>The App Store did not return the subscriptions. Try again later.</Muted>
      ) : (
        pro.products.map((product) => {
          const per = PRO_PRODUCTS.find((p) => p.id === product.id)?.per;
          const trial = freeTrial(product);
          return (
            <Pressable
              key={product.id}
              disabled={!!pro.busy}
              onPress={() => pro.buy(product.id)}
              style={({ pressed }) => ({
                backgroundColor: c.tint,
                borderRadius: 12,
                padding: 14,
                marginBottom: 12,
                alignItems: 'center',
                opacity: pro.busy ? 0.5 : pressed ? 0.7 : 1,
              })}>
              {pro.busy === product.id ? (
                <ActivityIndicator color="#FFFFFF" />
              ) : (
                <>
                  <Text style={{ color: '#FFFFFF', fontSize: 17, fontWeight: '600' }}>
                    {[trial, `${product.displayPrice} per ${per}`].filter(Boolean).join(' ')}
                  </Text>
                  <Text style={{ color: '#FFFFFF', opacity: 0.85, marginTop: 2 }}>
                    {per === 'year' ? 'Yearly' : 'Monthly'}, cancel any time
                  </Text>
                </>
              )}
            </Pressable>
          );
        })
      )}

      {pro.subscribed ? null : (
        <Button
          title="Restore purchases"
          kind="secondary"
          loading={pro.busy === 'restore'}
          disabled={!!pro.busy}
          onPress={pro.restore}
        />
      )}

      <Muted style={{ fontSize: 12, marginTop: 8 }}>
        Payment is charged to your Apple Account. A subscription renews automatically unless it is cancelled at least
        24 hours before the end of the current period. Manage or cancel it in your Apple Account settings.
      </Muted>
      <Muted style={{ fontSize: 12, marginTop: 8 }}>
        <Text style={{ color: c.tint }} onPress={() => Linking.openURL(TERMS_URL)}>
          Terms of Use
        </Text>
        {'  ·  '}
        <Text style={{ color: c.tint }} onPress={() => Linking.openURL(PRIVACY_URL)}>
          Privacy Policy
        </Text>
      </Muted>
    </ScrollView>
  );
}
