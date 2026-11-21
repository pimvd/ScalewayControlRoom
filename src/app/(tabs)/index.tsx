import { router } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, RefreshControl, Text, TextInput, View } from 'react-native';

import { Banner, Button, Muted, StateDot, Title, styles, useColors } from '@/components/ui';
import { DEMO_KEY } from '@/lib/demo';
import { filterServers, formatMoney, type Listed, type StateFilter } from '@/lib/logic';
import { useStore } from '@/lib/store';

const FILTERS: StateFilter[] = ['all', 'running', 'stopped'];

export default function InstancesScreen() {
  const store = useStore();
  const c = useColors();
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<StateFilter>('all');

  if (!store.loaded) {
    return <ActivityIndicator style={{ flex: 1 }} />;
  }
  if (store.keys.length === 0) {
    return <Welcome />;
  }

  const keyLabel = (id: string) => store.keys.find((k) => k.id === id)?.label ?? '';
  const visible = filterServers(store.servers, query, filter);

  const renderItem = ({ item }: { item: Listed }) => {
    const { item: server } = item;
    const price = store.prices[`${server.zone}/${server.commercialType}`];
    const details = [server.commercialType, server.zone];
    if (store.keys.length > 1) details.push(keyLabel(item.keyIds[0]));
    return (
      <Pressable
        onPress={() => router.push({ pathname: '/instance/[id]', params: { id: server.id } })}
        style={({ pressed }) => [
          { backgroundColor: c.card, borderRadius: 12, padding: 14, marginBottom: 8, opacity: pressed ? 0.6 : 1 },
        ]}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <StateDot state={server.state} />
          <Text style={{ color: c.text, fontSize: 17, fontWeight: '600', flexShrink: 1 }} numberOfLines={1}>
            {server.name}
          </Text>
          {server.state === 'running' && price !== undefined ? (
            <Text style={{ color: c.secondary, marginLeft: 'auto' }}>{formatMoney(price)}/h</Text>
          ) : null}
        </View>
        <Muted style={{ marginTop: 4, marginLeft: 18 }}>
          {server.state} · {details.join(' · ')}
        </Muted>
      </Pressable>
    );
  };

  return (
    <FlatList
      contentInsetAdjustmentBehavior="automatic"
      contentContainerStyle={styles.screen}
      data={visible}
      keyExtractor={(item) => item.item.id}
      renderItem={renderItem}
      refreshControl={<RefreshControl refreshing={store.refreshing} onRefresh={store.refresh} />}
      ListHeaderComponent={
        <View>
          <Title>Instances</Title>
          {store.problems
            .filter((p) => p.area === 'instances')
            .map((p) => (
              <Banner key={p.keyId}>
                {keyLabel(p.keyId)}: {p.message}
              </Banner>
            ))}
          <TextInput
            placeholder="Search name or tag"
            placeholderTextColor={c.secondary}
            value={query}
            onChangeText={setQuery}
            autoCapitalize="none"
            autoCorrect={false}
            clearButtonMode="while-editing"
            style={{ backgroundColor: c.card, color: c.text, borderRadius: 10, padding: 10, fontSize: 17 }}
          />
          <View style={{ flexDirection: 'row', gap: 8, marginVertical: 12 }}>
            {FILTERS.map((f) => (
              <Pressable
                key={f}
                onPress={() => setFilter(f)}
                style={{
                  paddingHorizontal: 14,
                  paddingVertical: 6,
                  borderRadius: 16,
                  backgroundColor: filter === f ? c.tint : c.card,
                }}>
                <Text style={{ color: filter === f ? '#FFFFFF' : c.text }}>{f[0].toUpperCase() + f.slice(1)}</Text>
              </Pressable>
            ))}
          </View>
        </View>
      }
      ListEmptyComponent={
        store.refreshing ? undefined : (
          <Muted style={{ textAlign: 'center', marginTop: 24 }}>
            {store.servers.length ? 'No instances match.' : 'No instances found for your keys.'}
          </Muted>
        )
      }
    />
  );
}

function Welcome() {
  const store = useStore();
  return (
    <View style={[styles.screen, { flex: 1, justifyContent: 'center' }]}>
      <Title>ControlRoom</Title>
      <Muted style={{ fontSize: 17, marginBottom: 24 }}>
        Start and stop your Scaleway instances, read your serverless logs, and see what your organization costs
        this month. Your keys stay on this iPhone.
      </Muted>
      <Button title="Add API key" onPress={() => router.push('/add-key')} />
      <Button title="Try the demo" kind="secondary" onPress={() => store.addKey(DEMO_KEY)} />
    </View>
  );
}
