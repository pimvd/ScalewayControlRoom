import { DarkTheme, DefaultTheme, Stack, ThemeProvider, router } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef } from 'react';
import { Platform, Settings, useColorScheme } from 'react-native';

import { DEMO_KEY } from '@/lib/demo';
import { ProProvider } from '@/lib/pro';
import { StoreProvider, useStore } from '@/lib/store';

// Opening a screen directly (a link, the screenshot route) still puts the tabs
// underneath, so Back works.
export const unstable_settings = { initialRouteName: '(tabs)' };

// The Screenshots workflow launches the app with `-screenshotRoute <path>`:
// iOS hands launch arguments to the app as settings. The demo is turned on
// and that screen opened, without a deep link and its "Open in" prompt.
function ScreenshotRoute() {
  const store = useStore();
  const done = useRef(false);
  useEffect(() => {
    const route = Platform.OS === 'ios' ? Settings.get('screenshotRoute') : undefined;
    if (typeof route !== 'string' || !store.loaded || done.current) return;
    done.current = true;
    (async () => {
      if (!store.keys.some((k) => k.demo)) await store.addKey(DEMO_KEY);
      router.replace(route as never);
    })();
  }, [store]);
  return null;
}

export default function RootLayout() {
  const dark = useColorScheme() === 'dark';
  return (
    <ThemeProvider value={dark ? DarkTheme : DefaultTheme}>
      <StoreProvider>
        <ProProvider>
          <ScreenshotRoute />
          <StatusBar style="auto" />
          <Stack>
            <Stack.Screen name="(tabs)" options={{ headerShown: false, title: 'Instances' }} />
            <Stack.Screen name="instance/[id]" options={{ title: '' }} />
            <Stack.Screen name="serverless/[id]" options={{ title: '' }} />
            <Stack.Screen name="logs" options={{ title: 'Logs' }} />
            <Stack.Screen name="add-key" options={{ presentation: 'modal', title: 'Add API key' }} />
            <Stack.Screen name="pro" options={{ presentation: 'modal', title: '' }} />
          </Stack>
        </ProProvider>
      </StoreProvider>
    </ThemeProvider>
  );
}
