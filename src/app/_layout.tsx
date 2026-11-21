import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useColorScheme } from 'react-native';

import { StoreProvider } from '@/lib/store';

// A deep link straight to a screen still gets the tabs underneath, so Back works.
export const unstable_settings = { initialRouteName: '(tabs)' };

export default function RootLayout() {
  const dark = useColorScheme() === 'dark';
  return (
    <ThemeProvider value={dark ? DarkTheme : DefaultTheme}>
      <StoreProvider>
        <StatusBar style="auto" />
        <Stack>
          <Stack.Screen name="(tabs)" options={{ headerShown: false, title: 'Instances' }} />
          <Stack.Screen name="instance/[id]" options={{ title: '' }} />
          <Stack.Screen name="serverless/[id]" options={{ title: '' }} />
          <Stack.Screen name="logs" options={{ title: 'Logs' }} />
          <Stack.Screen name="demo" options={{ headerShown: false }} />
          <Stack.Screen name="add-key" options={{ presentation: 'modal', title: 'Add API key' }} />
        </Stack>
      </StoreProvider>
    </ThemeProvider>
  );
}
