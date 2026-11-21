/// Shell commands the setup screens show, ready to copy.
public enum Commands {
    /// The permission sets the app can use. Scoped to the organization, a
    /// rule covers all current and future projects.
    public static let appPermissionSets = [
        "InstancesFullAccess",
        "BillingReadOnly",
        "ContainersReadOnly",
        "FunctionsReadOnly",
        "ObservabilityReadOnly",
    ]

    private static func rules(_ scope: String, _ sets: [String]) -> String {
        ([scope] + sets.enumerated().map { "rules.0.permission-set-names.\($0.offset)=\($0.element)" })
            .joined(separator: " \\\n  ")
    }

    /// scw CLI: an IAM application for the app, its policy and its API key.
    public static var keySetup: String {
        """
        ORG=$(scw config get default-organization-id)
        APP=$(scw iam application create name=controlroom description="ControlRoom iPhone app" -o template="{{ .ID }}")
        scw iam policy create name=controlroom application-id=$APP \\
          \(rules("rules.0.organization-id=$ORG", appPermissionSets))
        scw iam api-key create application-id=$APP description=controlroom
        echo "Organization ID: $ORG"
        """
    }

    /// scw CLI: a custom logs data source in the instance's project and
    /// region, and a separate key that may push to it.
    public static func logsSource(for server: Server) -> String {
        let name = "alloy-\(server.name)"
        return """
        DS_URL=$(scw cockpit data-source create name=controlroom-logs type=logs project-id=\(server.project) region=\(server.zone.region.rawValue) -o template="{{ .URL }}")
        APP=$(scw iam application create name=\(name) -o template="{{ .ID }}")
        scw iam policy create name=\(name) application-id=$APP \\
          \(rules("rules.0.project-ids.0=\(server.project)", ["ObservabilityFullAccess"]))
        scw iam api-key create application-id=$APP description=\(name)
        echo "Data source URL: $DS_URL"
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

    /// On the instance: Alloy config that ships the journal to Cockpit,
    /// labelled with the instance's ID so the app can find the lines again.
    public static func alloyConfig(for server: Server) -> String {
        """
        DS_URL="paste the data source URL"
        PUSH_KEY="paste the secret key"
        sudo tee /etc/alloy/config.alloy > /dev/null <<EOF
        loki.source.journal "system" {
          forward_to = [loki.write.cockpit.receiver]
        }

        loki.write "cockpit" {
          endpoint {
            url     = "${DS_URL}/loki/api/v1/push"
            headers = { "X-Auth-Token" = "${PUSH_KEY}" }
          }
          external_labels = {
            resource_id   = "\(server.id)",
            resource_name = "\(server.name)",
          }
        }
        EOF
        sudo systemctl restart alloy
        """
    }
}
