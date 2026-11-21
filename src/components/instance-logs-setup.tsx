import { Linking, Share, Text, View } from 'react-native';

import { Button, Muted, Section, useColors } from '@/components/ui';
import { SUPPORT_URL } from '@/lib/links';
import { regionOf, type Server } from '@/lib/types';

// Grafana Alloy config that ships the instance's journal to Cockpit, labelled
// with the instance's ID so this app can find the lines again.
export function alloyConfig(server: Server): string {
  return `loki.source.journal "system" {
  forward_to = [loki.write.cockpit.receiver]
}

loki.write "cockpit" {
  endpoint {
    url = "<LOGS DATA SOURCE URL>/loki/api/v1/push"
    headers = { "X-Auth-Token" = "<SECRET KEY>" }
  }
  external_labels = {
    resource_id   = "${server.id}",
    resource_name = "${server.name}",
  }
}
`;
}

const STEPS = [
  'In the Scaleway console, open Cockpit → Data sources and create a custom data source of type Logs in region REGION. Copy its URL.',
  'Create an API key that may push to Cockpit (an IAM application with the ObservabilityFullAccess permission set). Use a separate key, not the one in this app.',
  'On the instance, install Grafana Alloy (apt install alloy on Debian and Ubuntu, after adding the Grafana package repository).',
  'Put the configuration below in /etc/alloy/config.alloy, fill in the URL and the secret key, and run systemctl restart alloy.',
];

export function InstanceLogsSetup({ server }: { server: Server }) {
  const c = useColors();
  const config = alloyConfig(server);
  return (
    <View>
      <Text style={{ color: c.text, fontSize: 20, fontWeight: '600', marginBottom: 8 }}>
        This instance sends no logs
      </Text>
      <Muted style={{ fontSize: 15, marginBottom: 16 }}>
        Unlike serverless apps, instances do not send their logs to Cockpit by themselves. An agent on the instance has
        to push them. Once it does, they show up here. Logs you push yourself are billed by Scaleway as Cockpit custom
        data.
      </Muted>
      <Section title="Set it up">
        {STEPS.map((step, i) => (
          <View key={i} style={{ padding: 12, borderBottomWidth: 0.5, borderBottomColor: c.border }}>
            <Text style={{ color: c.text, fontSize: 15 }}>
              {i + 1}. {step.replace('REGION', regionOf(server.zone))}
            </Text>
          </View>
        ))}
      </Section>
      <Section title="Configuration for this instance">
        <Text selectable style={{ color: c.text, fontFamily: 'Menlo', fontSize: 12, padding: 12 }}>
          {config}
        </Text>
      </Section>
      <Button title="Share configuration" onPress={() => Share.share({ message: config })} />
      <Button title="Open the setup guide" kind="secondary" onPress={() => Linking.openURL(SUPPORT_URL)} />
    </View>
  );
}
