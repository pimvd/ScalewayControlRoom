import * as Clipboard from 'expo-clipboard';
import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';

import { useColors } from '@/components/ui';

// A block of shell commands with a Copy button. On a Mac with the same Apple
// account, the copied text can be pasted straight into Terminal.
export function CommandBlock({ title, command }: { title: string; command: string }) {
  const c = useColors();
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    await Clipboard.setStringAsync(command);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <View style={{ backgroundColor: c.card, borderRadius: 12, marginBottom: 16, overflow: 'hidden' }}>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          paddingHorizontal: 12,
          paddingVertical: 8,
          borderBottomWidth: 0.5,
          borderBottomColor: c.border,
        }}>
        <Text style={{ color: c.secondary, flex: 1, fontSize: 13 }}>{title}</Text>
        <Pressable onPress={copy} hitSlop={8}>
          <Text style={{ color: c.tint, fontWeight: '600' }}>{copied ? 'Copied' : 'Copy'}</Text>
        </Pressable>
      </View>
      <Text selectable style={{ color: c.text, fontFamily: 'Menlo', fontSize: 11, padding: 12 }}>
        {command}
      </Text>
    </View>
  );
}
