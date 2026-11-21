import { Linking, Text, View } from 'react-native';

import { CommandBlock } from '@/components/command';
import { Button, Muted, useColors } from '@/components/ui';
import { ALLOY_INSTALL_COMMANDS, alloyConfigCommands, logsSourceCommands } from '@/lib/commands';
import { SUPPORT_URL } from '@/lib/links';
import type { Server } from '@/lib/types';

// Shown when an instance has no logs in Cockpit: how to start sending them.
export function InstanceLogsSetup({ server }: { server: Server }) {
  const c = useColors();
  return (
    <View>
      <Text style={{ color: c.text, fontSize: 20, fontWeight: '600', marginBottom: 8 }}>
        This instance sends no logs
      </Text>
      <Muted style={{ fontSize: 15, marginBottom: 16 }}>
        Unlike serverless apps, instances do not send their logs to Cockpit by themselves: an agent on the instance has
        to push them. Set it up with the commands below and the logs show up here. Logs you push yourself are billed by
        Scaleway as Cockpit custom data.
      </Muted>
      <CommandBlock title="1. On your computer, with the scw CLI" command={logsSourceCommands(server)} />
      <Muted style={{ marginTop: -8, marginBottom: 16 }}>
        Keep the data source URL and the secret key it prints. The secret key is shown only once.
      </Muted>
      <CommandBlock title="2. On the instance: install Grafana Alloy" command={ALLOY_INSTALL_COMMANDS} />
      <CommandBlock title="3. On the instance: fill in the two values, then run" command={alloyConfigCommands(server)} />
      <Button title="Open the setup guide" kind="secondary" onPress={() => Linking.openURL(SUPPORT_URL)} />
    </View>
  );
}
