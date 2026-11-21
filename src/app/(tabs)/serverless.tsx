import { router } from 'expo-router';
import { FlatList, Pressable, RefreshControl, Text, View } from 'react-native';

import { Banner, Muted, Title, styles, useColors } from '@/components/ui';
import type { Listed } from '@/lib/logic';
import { usePro } from '@/lib/pro';
import { useStore } from '@/lib/store';
import type { ServerlessApp } from '@/lib/types';

export default function ServerlessScreen() {
  const store = useStore();
  const pro = usePro();
  const c = useColors();
  const keyLabel = (id: string) => store.keys.find((k) => k.id === id)?.label ?? '';

  if (!pro) {
    return (
      <View style={styles.screen}>
        <Title>Serverless</Title>
        <Muted>Serverless containers, functions and their logs are part of Pro.</Muted>
      </View>
    );
  }

  const renderItem = ({ item: listed }: { item: Listed<ServerlessApp> }) => {
    const app = listed.item;
    return (
      <Pressable
        onPress={() => router.push({ pathname: '/serverless/[id]', params: { id: app.id } })}
        style={({ pressed }) => ({
          backgroundColor: c.card,
          borderRadius: 12,
          padding: 14,
          marginBottom: 8,
          opacity: pressed ? 0.6 : 1,
        })}>
        <Text style={{ color: c.text, fontSize: 17, fontWeight: '600' }} numberOfLines={1}>
          {app.name}
        </Text>
        <Muted style={{ marginTop: 4 }}>
          {[app.kind, app.region, app.status, store.keys.length > 1 ? keyLabel(listed.keyIds[0]) : '']
            .filter(Boolean)
            .join(' · ')}
        </Muted>
      </Pressable>
    );
  };

  return (
    <FlatList
      contentInsetAdjustmentBehavior="automatic"
      contentContainerStyle={styles.screen}
      data={store.serverless}
      keyExtractor={(l) => l.item.id}
      renderItem={renderItem}
      refreshControl={<RefreshControl refreshing={store.refreshing} onRefresh={store.refresh} />}
      ListHeaderComponent={
        <View>
          <Title>Serverless</Title>
          {store.problems
            .filter((p) => p.area === 'serverless')
            .map((p) => (
              <Banner key={p.keyId}>
                {keyLabel(p.keyId)}: {p.message}
              </Banner>
            ))}
        </View>
      }
      ListEmptyComponent={
        store.refreshing ? undefined : (
          <Muted style={{ textAlign: 'center', marginTop: 24 }}>
            {store.keys.length ? 'No serverless containers or functions found for your keys.' : 'Add an API key first.'}
          </Muted>
        )
      }
    />
  );
}
