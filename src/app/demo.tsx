import { Redirect } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator } from 'react-native';

import { DEMO_KEY } from '@/lib/demo';
import { useStore } from '@/lib/store';

// controlroom://demo turns the demo on: used to take the store screenshots.
export default function DemoLink() {
  const store = useStore();
  const [done, setDone] = useState(false);
  const hasDemo = store.keys.some((k) => k.demo);

  useEffect(() => {
    // Wait for the stored keys, or adding the demo would replace them.
    if (!store.loaded) return;
    if (hasDemo) setDone(true);
    else store.addKey(DEMO_KEY).then(() => setDone(true));
  }, [store, hasDemo]);

  return done ? <Redirect href="/" /> : <ActivityIndicator style={{ flex: 1 }} />;
}
