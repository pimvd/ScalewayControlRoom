package com.pvd.scwcontrolroom.kit

/**
 * Shell commands the setup screens show, ready to copy. The iPhone app shows
 * the same ones (ios/Packages/ControlRoomKit/.../Commands.swift), and
 * website/support.html and docs/instance-logs-and-metrics.md repeat them:
 * keep them in step.
 */
object Commands {
    /**
     * The permission sets the app can use: start and stop instances, read
     * everything else (the Other tab lists every product, Cockpit included).
     * A rule may only hold sets of one scope type, so project-level and
     * organization-level sets get a rule each. Scoped to the organization, a
     * rule with project-level sets covers all current and future projects.
     */
    val projectPermissionSets = listOf("InstancesFullAccess", "AllProductsReadOnly")
    val organizationPermissionSets = listOf("BillingReadOnly")

    /** Policy rule arguments: `rules.N.<scope>` and its permission sets. */
    private fun rule(index: Int, scope: String, sets: List<String>): String =
        (listOf("rules.$index.$scope") + sets.mapIndexed { i, set -> "rules.$index.permission-set-names.$i=$set" })
            .joinToString(" \\\n  ")

    /**
     * scw CLI: an IAM application for the app, its policy and its API key.
     * The key is only created when the policy was: a key without
     * permissions sees nothing.
     */
    val keySetup: String
        get() = $$"""
            ORG=$(scw config get default-organization-id)
            APP=$(scw iam application create name=controlroom description="ControlRoom Android app" -o template="{{ .ID }}")
            scw iam policy create name=controlroom application-id=$APP \
              RULE0 \
              RULE1 &&
            scw iam api-key create application-id=$APP description=controlroom &&
            echo "Organization ID: $ORG"
            """.trimIndent()
            .replace("RULE0", rule(0, "organization-id=\$ORG", projectPermissionSets))
            .replace("RULE1", rule(1, "organization-id=\$ORG", organizationPermissionSets))

    /**
     * scw CLI: custom logs and metrics data sources in the instance's
     * project and region, and a separate key that may push to them.
     */
    fun cockpitSources(server: Server): String {
        val name = "alloy-${server.name}"
        val place = "project-id=${server.project} region=${server.zone.region.id}"
        return $$"""
            LOGS_URL=$(scw cockpit data-source create name=controlroom-logs type=logs $${place} -o template="{{ .URL }}")
            METRICS_URL=$(scw cockpit data-source create name=controlroom-metrics type=metrics $${place} -o template="{{ .URL }}")
            APP=$(scw iam application create name=$${name} -o template="{{ .ID }}")
            scw iam policy create name=$${name} application-id=$APP \
              RULE0 &&
            scw iam api-key create application-id=$APP description=$${name} &&
            echo "Logs URL: $LOGS_URL" &&
            echo "Metrics URL: $METRICS_URL"
            """.trimIndent()
            .replace("RULE0", rule(0, "project-ids.0=${server.project}", listOf("ObservabilityFullAccess")))
    }

    /**
     * On the instance (Debian or Ubuntu): Grafana Alloy from Grafana's
     * packages, allowed to read the journal.
     */
    val alloyInstall = """
        sudo mkdir -p /etc/apt/keyrings
        wget -qO- https://apt.grafana.com/gpg.key | gpg --dearmor | sudo tee /etc/apt/keyrings/grafana.gpg > /dev/null
        echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" | sudo tee /etc/apt/sources.list.d/grafana.list
        sudo apt-get update && sudo apt-get install -y alloy
        sudo usermod -aG systemd-journal alloy
        """.trimIndent()

    /**
     * On the instance: Alloy config that ships the journal and the basic
     * system metrics to Cockpit, labelled with the instance's ID so the app
     * can find them again. Few collectors, once a minute: pushed data is billed.
     * The config holds the push key, so only Alloy may read it; Grafana's
     * package leaves the service disabled, so it is enabled for reboots.
     */
    fun alloyConfig(server: Server): String {
        val labels = listOf(
            "  external_labels = {",
            "    resource_id   = \"${server.id}\",",
            "    resource_name = \"${server.name}\",",
            "  }",
        ).joinToString("\n")
        return $$"""
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
            LABELS
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
            LABELS
            }
            EOF
            sudo chown root:alloy /etc/alloy/config.alloy
            sudo chmod 640 /etc/alloy/config.alloy
            sudo systemctl enable alloy
            sudo systemctl restart alloy
            """.trimIndent()
            .replace("LABELS", labels)
    }
}
