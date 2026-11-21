import ControlRoomKit
import StoreKit

/// Pro: an auto-renewing subscription, checked with StoreKit on the device.
/// There is no server.
@MainActor
@Observable
final class ProStore {
    private(set) var subscribed = false
    private var updates: Task<Void, Never>?

    init() {
        // Purchases made elsewhere (another device, Ask to Buy, a renewal).
        updates = Task { [weak self] in
            for await result in Transaction.updates {
                if case .verified(let transaction) = result { await transaction.finish() }
                await self?.check()
            }
        }
        Task { await check() }
    }

    func check() async {
        var active = false
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result, ProProducts.all.contains(transaction.productID),
               transaction.revocationDate == nil {
                active = true
            }
        }
        subscribed = active
    }

    func restore() async {
        try? await AppStore.sync()
        await check()
    }

    /// Pro features are open when subscribed, and while the demo is the only
    /// key, so App Review and the screenshots see everything.
    func isPro(keys: [APIKey]) -> Bool {
        subscribed || (!keys.isEmpty && keys.allSatisfy(\.isDemo))
    }
}
