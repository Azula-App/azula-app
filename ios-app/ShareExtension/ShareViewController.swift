import UIKit
import UniformTypeIdentifiers

private let appGroupID = "group.app.azula"

/**
 * The `AzulaShare` extension's entry point: the OS instantiates this (a plain
 * `UIViewController` -- no custom compose UI, the system share-sheet chrome is
 * all the user sees) whenever they pick "Azula" from another app's share
 * sheet. A share extension can't foreground its host app (an iOS platform
 * restriction), so this has one job: copy each shared item's bytes into the
 * App Group container's `Inbox/` folder plus a `manifest.json` describing
 * them, then complete immediately. The host app drains that inbox itself the
 * next time it becomes active -- see `shared/src@ios/dev/azula/state/ShareInbox.kt`
 * `drainShareInbox()`, called from `ios-app/src/iosApp.swift` on
 * `scenePhase == .active`.
 *
 * NOTE: this compiles as ordinary Swift (no Kotlin in this target -- see the
 * doc comment on the `AzulaShare` target in `project.pbxproj`), but running the
 * actual share flow (picking Azula from another app's share sheet, then
 * switching back to Azula to see the picker) needs a live simulator/device and
 * both bundle ids provisioned with the App Group capability -- NOT exercised in
 * this environment. See the manual steps noted in `azula.entitlements` /
 * `AzulaShare.entitlements`.
 */
class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        handleShare()
    }

    private func handleShare() {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else {
            finish()
            return
        }
        let providers = items.flatMap { $0.attachments ?? [] }
        guard !providers.isEmpty else {
            finish()
            return
        }

        let group = DispatchGroup()
        var manifest: [[String: String]] = []
        let lock = NSLock()

        for provider in providers {
            group.enter()
            load(provider: provider) { entry in
                if let entry = entry {
                    lock.lock()
                    manifest.append(entry)
                    lock.unlock()
                }
                group.leave()
            }
        }

        group.notify(queue: .main) { [weak self] in
            self?.writeManifest(manifest)
            self?.finish()
        }
    }

    /**
     * Resolves one `NSItemProvider` into a manifest entry, writing its bytes
     * into the App Group inbox. Tries plain text first (so a shared URL/note
     * lands as `stageShareText`, not a file), then falls back to a file URL or
     * raw `Data` load -- how images/video/audio/generic files all arrive from
     * Photos/Files-backed senders (audio has no dedicated
     * `NSExtensionActivationRule` key -- see the doc comment on
     * `Info.plist`/`AzulaShare` target -- so it's matched as a generic file).
     */
    private func load(provider: NSItemProvider, completion: @escaping ([String: String]?) -> Void) {
        if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
           !provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { value, _ in
                let text = (value as? String) ?? ((value as? NSString) as String?)
                guard let text = text, let data = text.data(using: .utf8) else {
                    completion(nil)
                    return
                }
                completion(self.write(data: data, name: "shared.txt", mime: "text/plain"))
            }
            return
        }
        let fileTypes = [UTType.fileURL.identifier, UTType.item.identifier, UTType.data.identifier]
        for type in fileTypes where provider.hasItemConformingToTypeIdentifier(type) {
            provider.loadItem(forTypeIdentifier: type, options: nil) { value, _ in
                if let url = value as? URL, let data = try? Data(contentsOf: url) {
                    let mime = Self.mimeType(forPathExtension: url.pathExtension) ?? "application/octet-stream"
                    completion(self.write(data: data, name: url.lastPathComponent, mime: mime))
                } else if let data = value as? Data {
                    completion(self.write(data: data, name: "shared", mime: "application/octet-stream"))
                } else {
                    completion(nil)
                }
            }
            return
        }
        completion(nil)
    }

    private func write(data: Data, name: String, mime: String) -> [String: String]? {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID) else {
            return nil
        }
        let inbox = container.appendingPathComponent("Inbox", isDirectory: true)
        try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let fileName = "\(UUID().uuidString)-\(name)"
        do {
            try data.write(to: inbox.appendingPathComponent(fileName))
            return ["name": name, "mime": mime, "file": fileName]
        } catch {
            return nil
        }
    }

    /** Merges into any not-yet-drained manifest from a previous share (rather
     *  than clobbering it) so a rapid double-share doesn't lose the first one. */
    private func writeManifest(_ manifest: [[String: String]]) {
        guard !manifest.isEmpty,
              let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID)
        else { return }
        let manifestURL = container.appendingPathComponent("Inbox", isDirectory: true).appendingPathComponent("manifest.json")
        var existing: [[String: String]] = []
        if let data = try? Data(contentsOf: manifestURL),
           let decoded = try? JSONSerialization.jsonObject(with: data) as? [[String: String]] {
            existing = decoded
        }
        if let data = try? JSONSerialization.data(withJSONObject: existing + manifest) {
            try? data.write(to: manifestURL)
        }
    }

    private func finish() {
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }

    private static func mimeType(forPathExtension ext: String) -> String? {
        guard !ext.isEmpty, let type = UTType(filenameExtension: ext) else { return nil }
        return type.preferredMIMEType
    }
}
