import { useCallback, useEffect, useState } from 'react';
import { router } from 'expo-router';
import { Pressable, RefreshControl, ScrollView, Text, View } from 'react-native';

import { Banner, Muted, Row, Section, Title, styles, useColors } from '@/components/ui';
import { formatMoney, formatPeriod, recentPeriods, sumBy, total, uniqueOrganizations } from '@/lib/logic';
import { usePro } from '@/lib/pro';
import { clientFor, useStore } from '@/lib/store';
import type { Key, MonthlyCosts } from '@/lib/types';

const MONTHS = 6;

type Result = { key: Key; costs?: MonthlyCosts; error?: string };

export default function CostsScreen() {
  const { keys } = useStore();
  const pro = usePro();
  const c = useColors();
  const periods = recentPeriods(new Date(), pro ? MONTHS : 1);
  const [period, setPeriod] = useState(periods[0]);
  const [results, setResults] = useState<Result[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    const organizations = uniqueOrganizations(keys);
    setResults(
      await Promise.all(
        organizations.map((key) =>
          clientFor(key)
            .costs(period)
            .then((costs): Result => ({ key, costs }))
            .catch((e): Result => ({ key, error: e instanceof Error ? e.message : String(e) })),
        ),
      ),
    );
    setLoading(false);
  }, [keys, period]);

  useEffect(() => {
    load();
  }, [load]);

  const loaded = results.filter((r) => r.costs);
  const grandTotal = loaded.reduce((sum, r) => sum + total(r.costs!.items, r.costs!.discount), 0);

  return (
    <ScrollView
      contentInsetAdjustmentBehavior="automatic"
      contentContainerStyle={styles.screen}
      refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />}>
      <Title>Costs</Title>
      {keys.length === 0 ? <Muted>Add an API key to see your costs.</Muted> : null}

      {periods.length > 1 ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: 16 }}>
          {periods.map((p) => (
            <Pressable
              key={p}
              onPress={() => setPeriod(p)}
              style={{
                paddingHorizontal: 14,
                paddingVertical: 6,
                borderRadius: 16,
                marginRight: 8,
                backgroundColor: p === period ? c.tint : c.card,
              }}>
              <Text style={{ color: p === period ? '#FFFFFF' : c.text }}>{formatPeriod(p)}</Text>
            </Pressable>
          ))}
        </ScrollView>
      ) : null}

      {loaded.length > 0 ? (
        <View style={{ marginBottom: 24 }}>
          <Muted>{formatPeriod(period)}, excluding VAT</Muted>
          <Text style={{ color: c.text, fontSize: 40, fontWeight: '700' }}>
            {formatMoney(grandTotal, loaded[0].costs!.currency)}
          </Text>
        </View>
      ) : null}

      {results.map(({ key, costs, error }) => (
        <View key={key.organizationId}>
          {error ? (
            <Banner>
              {key.label}: {error}
            </Banner>
          ) : null}
          {costs && pro ? <Breakdown title={results.length > 1 ? key.label : undefined} costs={costs} /> : null}
        </View>
      ))}
      {loaded.length > 0 && !pro ? (
        <Section footer="Costs by category and by project, and the months before, are part of Pro.">
          <Row label="See the details with Pro" onPress={() => router.push('/pro')} last />
        </Section>
      ) : null}
      {loaded.length > 0 ? (
        <Muted>Scaleway updates these figures a few times a day. Your invoice is the final word.</Muted>
      ) : null}
    </ScrollView>
  );
}

function Breakdown({ title, costs }: { title?: string; costs: MonthlyCosts }) {
  const money = (amount: number) => formatMoney(amount, costs.currency);
  const section = (heading: string, rows: [string, number][]) => (
    <Section title={title ? `${title} · ${heading}` : heading}>
      {rows.map(([name, amount], i) => (
        <Row key={name} label={name} value={money(amount)} last={i === rows.length - 1} />
      ))}
    </Section>
  );
  const categories = sumBy(costs.items, 'category');
  if (costs.discount) categories.push(['Discounts', -costs.discount]);
  return (
    <>
      {section('By category', categories)}
      {section('By project', sumBy(costs.items, 'project'))}
    </>
  );
}
