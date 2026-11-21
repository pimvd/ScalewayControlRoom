import { Stack, router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Alert, ScrollView, View } from 'react-native';

import { Button, Muted, Row, Section, StateDot, styles } from '@/components/ui';
import { formatMoney, isBusy } from '@/lib/logic';
import { useStore } from '@/lib/store';
import type { ServerAction } from '@/lib/types';

const CONFIRM: Partial<Record<ServerAction, { title: string; message: string; button: string }>> = {
  poweroff: {
    title: 'Stop this instance?',
    message: 'Compute billing stops. Volumes and flexible IPs stay, and are still billed.',
    button: 'Stop',
  },
  reboot: { title: 'Reboot this instance?', message: 'It will be unreachable for a moment.', button: 'Reboot' },
};

export default function InstanceScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const store = useStore();
  const [pending, setPending] = useState<ServerAction | null>(null);
  const listed = store.servers.find((l) => l.item.id === id);

  if (!listed) {
    return (
      <View style={styles.screen}>
        <Muted>This instance is no longer listed.</Muted>
      </View>
    );
  }
  const { item: server } = listed;
  const price = store.prices[`${server.zone}/${server.commercialType}`];

  const run = async (action: ServerAction) => {
    setPending(action);
    try {
      await store.act(listed, action);
    } catch (error) {
      Alert.alert('Scaleway refused', error instanceof Error ? error.message : String(error));
    } finally {
      setPending(null);
    }
  };

  const confirmThen = (action: ServerAction) => {
    const confirm = CONFIRM[action];
    if (!confirm) return run(action);
    Alert.alert(confirm.title, confirm.message, [
      { text: 'Cancel', style: 'cancel' },
      { text: confirm.button, style: 'destructive', onPress: () => run(action) },
    ]);
  };

  const keyLabels = listed.keyIds.map((k) => store.keys.find((key) => key.id === k)?.label).join(', ');
  const busy = isBusy(server.state);

  return (
    <ScrollView contentInsetAdjustmentBehavior="automatic" contentContainerStyle={styles.screen}>
      <Stack.Screen options={{ title: server.name }} />
      <Section>
        <Row label="State" value={server.state} left={<StateDot state={server.state} />} />
        <Row label="Type" value={server.commercialType} />
        {price !== undefined ? <Row label="Price" value={`${formatMoney(price)} per hour`} /> : null}
        <Row label="Zone" value={server.zone} />
        {server.publicIps.length ? <Row label="Public IP" value={server.publicIps.join(', ')} /> : null}
        {server.tags.length ? <Row label="Tags" value={server.tags.join(', ')} /> : null}
        <Row label="Project" value={server.project} />
        <Row label="Seen by" value={keyLabels} last />
      </Section>

      <Section>
        <Row
          label="Logs"
          onPress={() => router.push({ pathname: '/logs', params: { kind: 'instance', id: server.id } })}
          last
        />
      </Section>

      {server.state === 'stopped' || server.state === 'stopped in place' ? (
        <Button title="Start" loading={pending === 'poweron'} disabled={!!pending} onPress={() => run('poweron')} />
      ) : null}
      {server.state === 'running' ? (
        <>
          <Button
            title="Stop"
            kind="danger"
            loading={pending === 'poweroff'}
            disabled={!!pending}
            onPress={() => confirmThen('poweroff')}
          />
          <Button
            title="Reboot"
            kind="secondary"
            loading={pending === 'reboot'}
            disabled={!!pending}
            onPress={() => confirmThen('reboot')}
          />
        </>
      ) : null}
      {busy ? <Muted style={{ textAlign: 'center' }}>Waiting for Scaleway…</Muted> : null}
    </ScrollView>
  );
}
