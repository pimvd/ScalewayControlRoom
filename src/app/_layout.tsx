import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useColorScheme } from 'react-native';

import { StoreProvider } from '@/lib/store';

export default function RootLayout() {
  const dark = useColorScheme() === 'dark';
  return (
    <ThemeProvider value={dark ? DarkTheme : DefaultTheme}>
      <StoreProvider>
        <StatusBar style="auto" />
        <Stack>
          <Stack.Screen name="(tabs)" options={{ headerShown: false, title: 'Instances' }} />
          <Stack.Screen name="instance/[id]" options={{ title: '' }} />
          <Stack.Screen name="add-key" options={{ presentation: 'modal', title: 'Add API key' }} />
        </Stack>
      </StoreProvider>
    </ThemeProvider>
  );
}
