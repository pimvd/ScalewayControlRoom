import ControlRoomKit
import StoreKit
import SwiftUI

/// The purchase screen. StoreKit's own subscription view shows the prices,
/// any free trial, the renewal terms and Restore purchases; the links to the
/// terms of use and privacy policy are ours.
struct ProView: View {
    @Environment(ProStore.self) private var pro
    @Environment(\.dismiss) private var dismiss
    @State private var showManage = false

    private static let features = [
        ("Serverless", "Your containers, functions and jobs in every region."),
        ("Everything else", "Databases, volumes, IPs, domains and more, grouped and searchable."),
        ("Logs and metrics", "From Cockpit, for serverless apps and instances: from the last 15 minutes up to 30 days."),
        ("Cost details", "Costs by category and by project, and the months before."),
        ("More keys", "Every organization and project you work on, side by side."),
    ]

    var body: some View {
        if pro.subscribed {
            NavigationStack {
                VStack(spacing: 16) {
                    Image(systemName: "checkmark.seal.fill").font(.system(size: 56)).foregroundStyle(.tint)
                    Text("You have Pro. Thank you!").font(.title3)
                    Button("Manage subscription") { showManage = true }
                }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                }
                .manageSubscriptionsSheet(isPresented: $showManage)
            }
        } else {
            // StoreKit adds its own close button when presented in a sheet.
            SubscriptionStoreView(productIDs: ProProducts.all) { marketing }
                .storeButton(.visible, for: .restorePurchases)
                .subscriptionStoreControlStyle(.picker)
                .subscriptionStorePolicyDestination(url: Links.terms, for: .termsOfService)
                .subscriptionStorePolicyDestination(url: Links.privacy, for: .privacyPolicy)
                .onInAppPurchaseCompletion { _, _ in await pro.check() }
        }
    }

    private var marketing: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("ControlRoom Pro").font(.largeTitle.bold())
            Text("Free: your instances, starting and stopping them, and this month's total cost. Pro adds:")
                .foregroundStyle(.secondary)
            ForEach(Self.features, id: \.0) { title, text in
                Label {
                    VStack(alignment: .leading) {
                        Text(title).font(.headline)
                        Text(text).font(.subheadline).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                } icon: {
                    Image(systemName: "checkmark").foregroundStyle(.tint)
                }
            }
        }
        .padding()
    }
}
