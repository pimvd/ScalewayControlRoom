import { Stack, router, useLocalSearchParams } from 'expo-router';
import { ScrollView, View } from 'react-native';

import { Muted, Row, Section, styles } from '@/components/ui';
import { useStore } from '@/lib/store';

export default function ServerlessAppScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const store = useStore();
  const listed = store.serverless.find((l) => l.item.id === id);

  if (!listed) {
    return (
      <View style={styles.screen}>
        <Muted>This app is no longer listed.</Muted>
      </View>
    );
  }
  const app = listed.item;
  const keyLabels = listed.keyIds.map((k) => store.keys.find((key) => key.id === k)?.label).join(', ');

  return (
    <ScrollView contentInsetAdjustmentBehavior="automatic" contentContainerStyle={styles.screen}>
      <Stack.Screen options={{ title: app.name }} />
      <Section>
        <Row label="Kind" value={app.kind === 'container' ? 'Serverless container' : 'Serverless function'} />
        <Row label="Status" value={app.status} />
        <Row label="Region" value={app.region} />
        <Row label="Scale" value={`${app.minScale} to ${app.maxScale}`} />
        {app.endpoint ? <Row label="Endpoint" value={app.endpoint} /> : null}
        <Row label="Project" value={app.projectId} />
        <Row label="Seen by" value={keyLabels} last />
      </Section>
      <Section>
        <Row
          label="Logs"
          onPress={() => router.push({ pathname: '/logs', params: { kind: 'serverless', id: app.id } })}
          last
        />
      </Section>
    </ScrollView>
  );
}
