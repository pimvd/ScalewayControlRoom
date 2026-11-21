import { router } from 'expo-router';
import { useState } from 'react';
import { Linking, ScrollView, Text, TextInput } from 'react-native';

import { CommandBlock } from '@/components/command';
import { ProGate } from '@/components/pro-gate';
import { Banner, Button, Muted, styles, useColors } from '@/components/ui';
import { keySetupCommands } from '@/lib/commands';
import { newKeyId, validateKey } from '@/lib/keys';
import { SUPPORT_URL } from '@/lib/links';
import { FREE_KEYS, usePro } from '@/lib/pro';
import { ApiError, scalewayClient } from '@/lib/scaleway';
import { useStore } from '@/lib/store';

export default function AddKeyScreen() {
  const store = useStore();
  const pro = usePro();
  const c = useColors();
  const [label, setLabel] = useState('');
  const [secretKey, setSecretKey] = useState('');
  const [organizationId, setOrganizationId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const realKeys = store.keys.filter((k) => !k.demo).length;
  const locked = !pro && realKeys >= FREE_KEYS;

  const save = async () => {
    const input = { label: label.trim(), secretKey: secretKey.trim(), organizationId: organizationId.trim() };
    const problem = validateKey(input);
    if (problem) return setError(problem);
    setSaving(true);
    setError(null);
    const key = { id: newKeyId(), ...input };
    try {
      // One cheap call tells a mistyped secret from a working one. A key that
      // may not list instances (403) is still useful for costs, so keep it.
      await scalewayClient(key).listServers('fr-par-1');
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 403)) {
        setSaving(false);
        return setError(
          e instanceof ApiError && e.status === 401
            ? 'Scaleway does not recognise this secret key.'
            : `Could not reach Scaleway: ${e instanceof Error ? e.message : e}`,
        );
      }
    }
    await store.addKey(key);
    router.back();
  };

  const field = (
    title: string,
    value: string,
    onChange: (v: string) => void,
    options: { secure?: boolean; placeholder?: string } = {},
  ) => (
    <>
      <Text style={{ color: c.secondary, marginBottom: 6 }}>{title}</Text>
      <TextInput
        value={value}
        onChangeText={onChange}
        placeholder={options.placeholder}
        placeholderTextColor={c.secondary}
        secureTextEntry={options.secure}
        autoCapitalize="none"
        autoCorrect={false}
        style={{ backgroundColor: c.card, color: c.text, borderRadius: 10, padding: 12, fontSize: 17, marginBottom: 16 }}
      />
    </>
  );

  if (locked) {
    return (
      <ScrollView contentContainerStyle={styles.screen}>
        <ProGate>More than one API key is part of Pro.</ProGate>
      </ScrollView>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.screen} keyboardShouldPersistTaps="handled">
      <Muted style={{ marginBottom: 16 }}>
        Give the app its own IAM application and API key. With the scw CLI, run the commands below on your computer:
        they print the secret key and the organization ID to paste here. Remove any permission set you do not want
        the app to have; the app hides what its key may not do.
      </Muted>
      <CommandBlock title="On your computer, with the scw CLI" command={keySetupCommands()} />
      <Button title="Setup guide (also without the CLI)" kind="secondary" onPress={() => Linking.openURL(SUPPORT_URL)} />
      {field('Name', label, setLabel, { placeholder: 'Work' })}
      {field('Secret key', secretKey, setSecretKey, { secure: true, placeholder: '1a2b3c4d-…' })}
      {field('Organization ID', organizationId, setOrganizationId, { placeholder: '1a2b3c4d-…' })}
      {error ? <Banner>{error}</Banner> : null}
      <Button title="Save" loading={saving} onPress={save} />
    </ScrollView>
  );
}
