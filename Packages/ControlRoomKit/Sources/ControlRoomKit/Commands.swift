/// Shell commands the setup screens show, ready to copy.
public enum Commands {
    /// The permission sets the app can use. A rule may only hold sets of one
    /// scope type, so project-level and organization-level sets get a rule
    /// each. Scoped to the organization, a rule with project-level sets covers
    /// all current and future projects.
    public static let projectPermissionSets = [
        "InstancesFullAccess",
        "ContainersReadOnly",
        "FunctionsReadOnly",
        "ObservabilityReadOnly",
    ]
    public static let organizationPermissionSets = ["BillingReadOnly"]

    /// Policy rule arguments: `rules.N.<scope>` and its permission sets.
    private static func rule(_ index: Int, _ scope: String, _ sets: [String]) -> String {
        (["rules.\(index).\(scope)"] + sets.enumerated().map { "rules.\(index).permission-set-names.\($0.offset)=\($0.element)" })
            .joined(separator: " \\\n  ")
    }

    /// scw CLI: an IAM application for the app, its policy and its API key.
    /// The key is only created when the policy was: a key without
    /// permissions sees nothing.
    public static var keySetup: String {
        """
        ORG=$(scw config get default-organization-id)
        APP=$(scw iam application create name=controlroom description="ControlRoom iPhone app" -o template="{{ .ID }}")
        scw iam policy create name=controlroom application-id=$APP \\
          \(rule(0, "organization-id=$ORG", projectPermissionSets)) \\
          \(rule(1, "organization-id=$ORG", organizationPermissionSets)) &&
        scw iam api-key create application-id=$APP description=controlroom &&
        echo "Organization ID: $ORG"
        """
    }

    /// scw CLI: custom logs and metrics data sources in the instance's
    /// project and region, and a separate key that may push to them.
    public static func cockpitSources(for server: Server) -> String {
        let name = "alloy-\(server.name)"
        let place = "project-id=\(server.project) region=\(server.zone.region.rawValue)"
        return """
        LOGS_URL=$(scw cockpit data-source create name=controlroom-logs type=logs \(place) -o template="{{ .URL }}")
        METRICS_URL=$(scw cockpit data-source create name=controlroom-metrics type=metrics \(place) -o template="{{ .URL }}")
        APP=$(scw iam application create name=\(name) -o template="{{ .ID }}")
        scw iam policy create name=\(name) application-id=$APP \\
          \(rule(0, "project-ids.0=\(server.project)", ["ObservabilityFullAccess"])) &&
        scw iam api-key create application-id=$APP description=\(name) &&
        echo "Logs URL: $LOGS_URL" &&
        echo "Metrics URL: $METRICS_URL"
        """
    }

    /// On the instance (Debian or Ubuntu): Grafana Alloy from Grafana's
    /// packages, allowed to read the journal.
    public static let alloyInstall = """
        sudo mkdir -p /etc/apt/keyrings
        wget -qO- https://apt.grafana.com/gpg.key | gpg --dearmor | sudo tee /etc/apt/keyrings/grafana.gpg > /dev/null
        echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" | sudo tee /etc/apt/sources.list.d/grafana.list
        sudo apt-get update && sudo apt-get install -y alloy
        sudo usermod -aG systemd-journal alloy
        """

    /// On the instance: Alloy config that ships the journal and the basic
    /// system metrics to Cockpit, labelled with the instance's ID so the app
    /// can find them again. Few collectors, once a minute: pushed data is billed.
    public static func alloyConfig(for server: Server) -> String {
        let labels = """
          external_labels = {
            resource_id   = "\(server.id)",
            resource_name = "\(server.name)",
          }
        """
        return """
        LOGS_URL="paste the logs URL"
        METRICS_URL="paste the metrics URL"
        PUSH_KEY="paste the secret key"
        sudo tee /etc/alloy/config.alloy > /dev/null <<EOF
        loki.source.journal "system" {
          forward_to = [loki.write.cockpit.receiver]
        }

        loki.write "cockpit" {
          endpoint {
            url     = "${LOGS_URL}/loki/api/v1/push"
            headers = { "X-Auth-Token" = "${PUSH_KEY}" }
          }
        \(labels)
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
        \(labels)
        }
        EOF
        sudo systemctl restart alloy
        """
    }
}
