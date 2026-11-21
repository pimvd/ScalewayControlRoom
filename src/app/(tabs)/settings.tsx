import { router } from 'expo-router';
import { Alert, Linking, ScrollView } from 'react-native';

import { Row, Section, Title, styles } from '@/components/ui';
import { DEMO_KEY } from '@/lib/demo';
import { APP_VERSION, PRIVACY_URL, SUPPORT_URL, TERMS_URL } from '@/lib/links';
import { useStore } from '@/lib/store';

export default function SettingsScreen() {
  const store = useStore();
  const hasDemo = store.keys.some((k) => k.demo);

  const confirmRemove = (id: string, label: string) =>
    Alert.alert(`Remove ${label}?`, 'The key is deleted from this iPhone. It stays valid at Scaleway.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Remove', style: 'destructive', onPress: () => store.removeKey(id) },
    ]);

  return (
    <ScrollView contentInsetAdjustmentBehavior="automatic" contentContainerStyle={styles.screen}>
      <Title>Settings</Title>

      <Section
        title="API keys"
        footer="Keys are stored in the iPhone keychain and sent only to Scaleway. Tap a key to remove it.">
        {store.keys.map((k) => (
          <Row
            key={k.id}
            label={k.label}
            value={k.demo ? 'Made-up data' : k.organizationId.slice(0, 8)}
            onPress={() => confirmRemove(k.id, k.label)}
          />
        ))}
        <Row label="Add API key" onPress={() => router.push('/add-key')} last={hasDemo} />
        {hasDemo ? null : <Row label="Try the demo" onPress={() => store.addKey(DEMO_KEY)} last />}
      </Section>

      <Section title="Help">
        <Row label="Setup guide" onPress={() => Linking.openURL(SUPPORT_URL)} />
        <Row label="Privacy policy" onPress={() => Linking.openURL(PRIVACY_URL)} />
        <Row label="Terms of use" onPress={() => Linking.openURL(TERMS_URL)} last />
      </Section>

      <Section
        title="About"
        footer="ControlRoom is an independent app. It is not made, endorsed or supported by Scaleway.">
        <Row label="Version" value={APP_VERSION} last />
      </Section>
    </ScrollView>
  );
}
