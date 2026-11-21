# Logs and metrics of an instance

Serverless apps send their logs and metrics to Cockpit by themselves;
instances do not. An agent on the instance, Grafana Alloy, has to push them.
Once it does, the instance's Logs and Metrics screens in the app show them.

The app shows the same steps, filled in for the instance, on an instance's
Logs or Metrics screen while it has none. The commands below come from
`Commands.swift` (`cockpitSources`, `alloyInstall`, `alloyConfig`); change
them there first.

What gets pushed: the system journal, and CPU, memory, load, disk and network
metrics once a minute, labelled `resource_id=<instance ID>` so the app finds
them. Scaleway bills pushed data as Cockpit custom data.

## 1. On your computer: data sources and a push key

With the [scw CLI](https://github.com/scaleway/scaleway-cli), logged in. Fill
in the instance's name, project ID and region (`fr-par`, `nl-ams`, `pl-waw`).

```sh
LOGS_URL=$(scw cockpit data-source create name=controlroom-logs type=logs project-id=<project ID> region=<region> -o template="{{ .URL }}")
METRICS_URL=$(scw cockpit data-source create name=controlroom-metrics type=metrics project-id=<project ID> region=<region> -o template="{{ .URL }}")
APP=$(scw iam application create name=alloy-<instance name> -o template="{{ .ID }}")
scw iam policy create name=alloy-<instance name> application-id=$APP \
  rules.0.project-ids.0=<project ID> \
  rules.0.permission-set-names.0=ObservabilityFullAccess &&
scw iam api-key create application-id=$APP description=alloy-<instance name> &&
echo "Logs URL: $LOGS_URL" &&
echo "Metrics URL: $METRICS_URL"
```

Keep the two URLs and the secret key. The secret key is shown only once. The
key can only reach Cockpit, in that one project, so it may live on the instance.

## 2. On the instance: install Alloy

Debian or Ubuntu, from Grafana's packages. Alloy joins `systemd-journal` so it
may read the journal.

```sh
sudo mkdir -p /etc/apt/keyrings
wget -qO- https://apt.grafana.com/gpg.key | gpg --dearmor | sudo tee /etc/apt/keyrings/grafana.gpg > /dev/null
echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" | sudo tee /etc/apt/sources.list.d/grafana.list
sudo apt-get update && sudo apt-get install -y alloy
sudo usermod -aG systemd-journal alloy
```

## 3. On the instance: configure and start Alloy

Fill in the two URLs, the secret key and the instance's ID and name, then run:

```sh
LOGS_URL="<logs URL>"
METRICS_URL="<metrics URL>"
PUSH_KEY="<secret key>"
sudo tee /etc/alloy/config.alloy > /dev/null <<EOF
loki.source.journal "system" {
  forward_to = [loki.write.cockpit.receiver]
}

loki.write "cockpit" {
  endpoint {
    url     = "${LOGS_URL}/loki/api/v1/push"
    headers = { "X-Auth-Token" = "${PUSH_KEY}" }
  }
  external_labels = {
    resource_id   = "<instance ID>",
    resource_name = "<instance name>",
  }
}

prometheus.exporter.unix "node" {
  set_collectors = ["cpu", "meminfo", "loadavg", "filesystem", "netdev"]
}

prometheus.scrape "node" {
  targets         = prometheus.exporter.unix.node.targets
  scrape_interval = "60s"
  forward_to      = [prometheus.remote_write.cockpit.receiver]
}

prometheus.remote_write "cockpit" {
  endpoint {
    url     = "${METRICS_URL}/api/v1/push"
    headers = { "X-Auth-Token" = "${PUSH_KEY}" }
  }
  external_labels = {
    resource_id   = "<instance ID>",
    resource_name = "<instance name>",
  }
}
EOF
sudo chown root:alloy /etc/alloy/config.alloy
sudo chmod 640 /etc/alloy/config.alloy
sudo systemctl enable alloy
sudo systemctl restart alloy
```

The config holds the push key, so only Alloy may read it. Grafana's package
leaves Alloy disabled; `enable` starts it again after a reboot.

## Check it

- `systemctl status alloy` says `active (running)`.
- `journalctl -u alloy -n 50` shows no push errors (401 means a wrong key,
  404 a wrong URL).
- After a minute or two, the instance's Logs and Metrics screens in the app
  show data. Pull to refresh.

## Remove it

On the instance: `sudo apt-get remove -y alloy`. On your computer, delete the
two `controlroom-*` data sources and the `alloy-<instance name>` IAM
application (`scw cockpit data-source delete`, `scw iam application delete`),
which also removes its key.
