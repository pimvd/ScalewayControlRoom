import type { ReactNode } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View, useColorScheme } from 'react-native';

import type { Server } from '@/lib/types';

const LIGHT = {
  background: '#F2F2F7',
  card: '#FFFFFF',
  text: '#111111',
  secondary: '#6B7280',
  border: '#E5E7EB',
  tint: '#0E8A63',
  danger: '#D92D20',
  warning: '#B54708',
  warningBackground: '#FEF0C7',
};

const DARK: typeof LIGHT = {
  background: '#000000',
  card: '#1C1C1E',
  text: '#F5F5F5',
  secondary: '#9CA3AF',
  border: '#2C2C2E',
  tint: '#34C08B',
  danger: '#F97066',
  warning: '#FEC84B',
  warningBackground: '#3A2A05',
};

export type Colors = typeof LIGHT;

export function useColors(): Colors {
  return useColorScheme() === 'dark' ? DARK : LIGHT;
}

const STATE_COLORS: Record<Server['state'], string> = {
  running: '#16A34A',
  starting: '#F59E0B',
  stopping: '#F59E0B',
  locked: '#D92D20',
  'stopped in place': '#9CA3AF',
  stopped: '#9CA3AF',
};

export function StateDot({ state }: { state: Server['state'] }) {
  return <View style={[styles.dot, { backgroundColor: STATE_COLORS[state] }]} />;
}

export function Title({ children }: { children: ReactNode }) {
  const c = useColors();
  return <Text style={[styles.title, { color: c.text }]}>{children}</Text>;
}

export function Muted({ children, style }: { children: ReactNode; style?: object }) {
  const c = useColors();
  return <Text style={[styles.muted, { color: c.secondary }, style]}>{children}</Text>;
}

export function Section({ title, footer, children }: { title?: string; footer?: ReactNode; children: ReactNode }) {
  const c = useColors();
  return (
    <View style={styles.section}>
      {title ? <Text style={[styles.sectionTitle, { color: c.secondary }]}>{title.toUpperCase()}</Text> : null}
      <View style={[styles.card, { backgroundColor: c.card }]}>{children}</View>
      {footer ? <Muted style={styles.footer}>{footer}</Muted> : null}
    </View>
  );
}

export function Row({
  label,
  value,
  onPress,
  destructive,
  left,
  last,
}: {
  label: string;
  value?: string;
  onPress?: () => void;
  destructive?: boolean;
  left?: ReactNode;
  last?: boolean;
}) {
  const c = useColors();
  return (
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      style={({ pressed }) => [
        styles.row,
        !last && { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: c.border },
        pressed && { opacity: 0.6 },
      ]}>
      {left}
      <Text style={[styles.rowLabel, { color: destructive ? c.danger : onPress && !value ? c.tint : c.text }]}>
        {label}
      </Text>
      {value ? (
        <Text style={[styles.rowValue, { color: c.secondary }]} numberOfLines={1}>
          {value}
        </Text>
      ) : null}
    </Pressable>
  );
}

export function Button({
  title,
  onPress,
  kind = 'primary',
  loading,
  disabled,
}: {
  title: string;
  onPress: () => void;
  kind?: 'primary' | 'secondary' | 'danger';
  loading?: boolean;
  disabled?: boolean;
}) {
  const c = useColors();
  const color = kind === 'danger' ? c.danger : c.tint;
  const filled = kind === 'primary';
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || loading}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: filled ? color : c.card, borderColor: color, opacity: disabled ? 0.4 : pressed ? 0.7 : 1 },
      ]}>
      {loading ? (
        <ActivityIndicator color={filled ? '#FFFFFF' : color} />
      ) : (
        <Text style={[styles.buttonText, { color: filled ? '#FFFFFF' : color }]}>{title}</Text>
      )}
    </Pressable>
  );
}

export function Banner({ children }: { children: ReactNode }) {
  const c = useColors();
  return (
    <View style={[styles.banner, { backgroundColor: c.warningBackground }]}>
      <Text style={{ color: c.warning }}>{children}</Text>
    </View>
  );
}

export const styles = StyleSheet.create({
  title: { fontSize: 34, fontWeight: '700', marginBottom: 12 },
  muted: { fontSize: 14 },
  section: { marginBottom: 24 },
  sectionTitle: { fontSize: 13, marginLeft: 16, marginBottom: 6 },
  card: { borderRadius: 12, overflow: 'hidden' },
  footer: { marginHorizontal: 16, marginTop: 6, fontSize: 13 },
  row: { flexDirection: 'row', alignItems: 'center', minHeight: 48, paddingHorizontal: 16, paddingVertical: 10, gap: 10 },
  rowLabel: { fontSize: 17, flexShrink: 1 },
  rowValue: { fontSize: 17, marginLeft: 'auto', flexShrink: 1, textAlign: 'right' },
  button: { borderRadius: 12, borderWidth: 1.5, paddingVertical: 14, alignItems: 'center', marginBottom: 12 },
  buttonText: { fontSize: 17, fontWeight: '600' },
  banner: { borderRadius: 12, padding: 12, marginBottom: 12 },
  dot: { width: 10, height: 10, borderRadius: 5 },
  screen: { padding: 16, paddingBottom: 40 },
});
