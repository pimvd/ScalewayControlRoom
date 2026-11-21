import { router } from 'expo-router';
import { View } from 'react-native';

import { Button, Muted } from '@/components/ui';

// Shown in place of a Pro feature: what it is, and the way to get it.
export function ProGate({ children }: { children: string }) {
  return (
    <View>
      <Muted style={{ fontSize: 16, marginBottom: 16 }}>{children}</Muted>
      <Button title="Get Pro" onPress={() => router.push('/pro')} />
    </View>
  );
}
