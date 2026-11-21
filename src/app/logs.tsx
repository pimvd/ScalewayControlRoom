import { useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { FlatList, Pressable, RefreshControl, Text, View } from 'react-native';

import { InstanceLogsSetup } from '@/components/instance-logs-setup';
import { ProGate } from '@/components/pro-gate';
import { Banner, Muted, styles, useColors } from '@/components/ui';
import { usePro } from '@/lib/pro';
import { isForbidden } from '@/lib/scaleway';
import { useStore, withKeys } from '@/lib/store';
import { regionOf, type LogResult, type LogTarget } from '@/lib/types';

const RANGES = [
  { label: '15 min', minutes: 15 },
  { label: '1 hour', minutes: 60 },
  { label: '24 hours', minutes: 1440 },
];

export default function LogsScreen() {
  const { kind, id } = useLocalSearchParams<{ kind: 'instance' | 'serverless'; id: string }>();
  const store = useStore();
  const pro = usePro();
  const c = useColors();
  const [minutes, setMinutes] = useState(60);
  const [result, setResult] = useState<LogResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const instance = kind === 'instance' ? store.servers.find((l) => l.item.id === id) : undefined;
  const app = kind === 'serverless' ? store.serverless.find((l) => l.item.id === id) : undefined;
  const keyIds = (instance ?? app)?.keyIds;
  const target: LogTarget | undefined = instance
    ? { projectId: instance.item.project, region: regionOf(instance.item.zone), resourceId: id }
    : app
      ? { projectId: app.item.projectId, region: app.item.region, resourceId: id }
      : undefined;

  const load = useCallback(async () => {
    if (!target || !keyIds) return;
    setLoading(true);
    setError(null);
    try {
      setResult(await withKeys(store.keys, keyIds, (client) => client.logs(target, minutes)));
    } catch (e) {
      setError(
        isForbidden(e)
          ? 'This key may not read Cockpit. Give its application the ObservabilityReadOnly permission set.'
          : e instanceof Error
            ? e.message
            : String(e),
      );
    } finally {
      setLoading(false);
    }
  }, [target?.projectId, target?.region, id, minutes, store.keys, keyIds?.join()]);

  useEffect(() => {
    load();
  }, [load]);

  if (!pro) {
    return (
      <View style={styles.screen}>
        <ProGate>Logs from Cockpit are part of Pro.</ProGate>
      </View>
    );
  }
  if (!target) {
    return (
      <View style={styles.screen}>
        <Muted>This resource is no longer listed.</Muted>
      </View>
    );
  }

  const empty = result && result.lines.length === 0;

  return (
    <FlatList
      contentInsetAdjustmentBehavior="automatic"
      contentContainerStyle={styles.screen}
      data={result?.lines ?? []}
      keyExtractor={(line, i) => `${line.time}-${i}`}
      refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />}
      ListHeaderComponent={
        <View>
          <View style={{ flexDirection: 'row', gap: 8, marginBottom: 12 }}>
            {RANGES.map((r) => (
              <Pressable
                key={r.minutes}
                onPress={() => setMinutes(r.minutes)}
                style={{
                  paddingHorizontal: 14,
                  paddingVertical: 6,
                  borderRadius: 16,
                  backgroundColor: minutes === r.minutes ? c.tint : c.card,
                }}>
                <Text style={{ color: minutes === r.minutes ? '#FFFFFF' : c.text }}>{r.label}</Text>
              </Pressable>
            ))}
          </View>
          {error ? <Banner>{error}</Banner> : null}
        </View>
      }
      renderItem={({ item }) => (
        <View style={{ paddingVertical: 6, borderBottomWidth: 0.5, borderBottomColor: c.border }}>
          <Muted style={{ fontSize: 12 }}>{new Date(item.time).toLocaleTimeString('en-GB')}</Muted>
          <Text style={{ color: c.text, fontFamily: 'Menlo', fontSize: 13 }}>{item.line}</Text>
        </View>
      )}
      ListFooterComponent={
        empty ? (
          kind === 'instance' ? (
            <InstanceLogsSetup server={instance!.item} />
          ) : (
            <Muted>
              No logs in this period. Everything the app writes to standard output and standard error shows up here,
              as Scaleway sends serverless logs to Cockpit by itself.
            </Muted>
          )
        ) : undefined
      }
    />
  );
}
