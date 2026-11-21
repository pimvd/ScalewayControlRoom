// Shell commands the setup screens show, ready to copy. Kept here, free of
// React, so tests can check what gets filled in.
import { regionOf, type Server } from './types';

// The permission sets the app can use. Scoped to the organization, a rule
// covers all current and future projects.
export const APP_PERMISSION_SETS = [
  'InstancesFullAccess',
  'BillingReadOnly',
  'ContainersReadOnly',
  'FunctionsReadOnly',
  'ObservabilityReadOnly',
];

function rules(scope: string, sets: string[]): string {
  return [scope, ...sets.map((set, i) => `rules.0.permission-set-names.${i}=${set}`)].join(' \\\n  ');
}

// scw CLI: an IAM application for the app, its policy and its API key.
export function keySetupCommands(): string {
  return `ORG=$(scw config get default-organization-id)
APP=$(scw iam application create name=controlroom description="ControlRoom iPhone app" -o template="{{ .ID }}")
scw iam policy create name=controlroom application-id=$APP \\
  ${rules('rules.0.organization-id=$ORG', APP_PERMISSION_SETS)}
scw iam api-key create application-id=$APP description=controlroom
echo "Organization ID: $ORG"`;
}

// scw CLI: a custom logs data source in the instance's project and region,
// and a separate key that may push to it.
export function logsSourceCommands(server: Server): string {
  const name = `alloy-${server.name}`;
  return `DS_URL=$(scw cockpit data-source create name=controlroom-logs type=logs project-id=${server.project} region=${regionOf(server.zone)} -o template="{{ .URL }}")
APP=$(scw iam application create name=${name} -o template="{{ .ID }}")
scw iam policy create name=${name} application-id=$APP \\
  ${rules(`rules.0.project-ids.0=${server.project}`, ['ObservabilityFullAccess'])}
scw iam api-key create application-id=$APP description=${name}
echo "Data source URL: $DS_URL"`;
}

// On the instance (Debian or Ubuntu): Grafana Alloy from Grafana's packages,
// allowed to read the journal.
export const ALLOY_INSTALL_COMMANDS = `sudo mkdir -p /etc/apt/keyrings
wget -qO- https://apt.grafana.com/gpg.key | gpg --dearmor | sudo tee /etc/apt/keyrings/grafana.gpg > /dev/null
echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" | sudo tee /etc/apt/sources.list.d/grafana.list
sudo apt-get update && sudo apt-get install -y alloy
sudo usermod -aG systemd-journal alloy`;

// On the instance: Alloy config that ships the journal to Cockpit, labelled
// with the instance's ID so this app can find the lines again.
export function alloyConfigCommands(server: Server): string {
  return `DS_URL="paste the data source URL"
PUSH_KEY="paste the secret key"
sudo tee /etc/alloy/config.alloy > /dev/null <<EOF
loki.source.journal "system" {
  forward_to = [loki.write.cockpit.receiver]
}

loki.write "cockpit" {
  endpoint {
    url     = "\${DS_URL}/loki/api/v1/push"
    headers = { "X-Auth-Token" = "\${PUSH_KEY}" }
  }
  external_labels = {
    resource_id   = "${server.id}",
    resource_name = "${server.name}",
  }
}
EOF
sudo systemctl restart alloy`;
}
