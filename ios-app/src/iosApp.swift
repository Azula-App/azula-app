import SwiftUI
import KotlinModules

// The iroh-backed transport requires the `IrohLib` Swift package (added in Xcode;
// see docs/ios-iroh.md). It is guarded by `#if canImport(IrohLib)` so the app
// still builds and runs without the package — in that case no transport is
// registered and the Kotlin layer falls back to its in-app demo transport.
#if canImport(IrohLib)
import IrohLib

// MARK: - IrohLib-backed transport, bridged to the Kotlin `SwiftIrohTransport`.
// IrohLib mirrors the iroh 1.0 surface; confirm exact signatures against the
// generated module if the compiler disagrees.

final class IrohStreamBridge: SwiftP2pStream {
    private let bi: BiStream
    private let sender: SendStream
    private let receiver: RecvStream
    private var buffer = Data()
    private var eof = false

    init(_ bi: BiStream) {
        self.bi = bi
        self.sender = bi.send()
        self.receiver = bi.recv()
    }

    func sendLine(line: String, onDone: @escaping (String?) -> Void) {
        Task {
            do {
                try await sender.writeAll(buf: Data((line + "\n").utf8))
                onDone(nil)
            } catch { onDone("\(error)") }
        }
    }

    func receiveLine(onLine: @escaping (String?) -> Void) {
        Task {
            while true {
                if let nl = buffer.firstIndex(of: 0x0a) {
                    let lineData = buffer.subdata(in: buffer.startIndex..<nl)
                    buffer.removeSubrange(buffer.startIndex...nl)
                    onLine(String(data: lineData, encoding: .utf8) ?? "")
                    return
                }
                if eof {
                    let rest = buffer.isEmpty ? nil : String(data: buffer, encoding: .utf8)
                    buffer.removeAll()
                    onLine(rest)
                    return
                }
                do {
                    if let chunk = try await receiver.read(maxSize: 65536), !chunk.isEmpty {
                        buffer.append(chunk)
                    } else {
                        eof = true
                    }
                } catch {
                    onLine(nil)
                    return
                }
            }
        }
    }

    func close() {
        Task { try? await sender.finish() }
    }
}

final class IrohTransportBridge: SwiftIrohTransport {
    private var endpoint: Endpoint?
    private var incomingCb: ((String, String, SwiftP2pStream) -> Void)?

    func bind(alpns: [String], onReady: @escaping (String?, String?) -> Void) {
        Task {
            do {
                let options = EndpointOptions(preset: presetN0(), alpns: alpns.map { Data($0.utf8) })
                let ep = try await Endpoint.bind(options: options)
                self.endpoint = ep
                onReady(ep.id().description, nil)
                Task { try? await ep.online() }
                self.acceptLoop(ep)
            } catch {
                onReady(nil, "\(error)")
            }
        }
    }

    func myTicket(onResult: @escaping (String?, String?) -> Void) {
        guard let ep = endpoint else { onResult(nil, "not bound"); return }
        do {
            let ticket = try EndpointTicket.fromAddr(addr: ep.addr())
            onResult(ticket.description, nil)
        } catch { onResult(nil, "\(error)") }
    }

    func connect(ticket: String, alpn: String, onStream: @escaping (SwiftP2pStream?, String?) -> Void) {
        guard let ep = endpoint else { onStream(nil, "not bound"); return }
        Task {
            do {
                let addr = try EndpointTicket.fromString(str: ticket).endpointAddr()
                let conn = try await ep.connect(addr: addr, alpn: Data(alpn.utf8))
                let bi = try await conn.openBi()
                onStream(IrohStreamBridge(bi), nil)
            } catch { onStream(nil, "\(error)") }
        }
    }

    func onIncoming(cb: @escaping (String, String, SwiftP2pStream) -> Void) {
        incomingCb = cb
    }

    func shutdown() {
        Task { try? await endpoint?.shutdown() }
    }

    private func acceptLoop(_ ep: Endpoint) {
        Task {
            while true {
                do {
                    let incoming = try await ep.acceptNext()
                    Task {
                        do {
                            let accepting = try await incoming.accept()
                            let conn = try await accepting.connect()
                            let alpn = String(data: try await conn.alpn(), encoding: .utf8) ?? ""
                            let bi = try await conn.acceptBi()
                            self.incomingCb?(conn.remoteId().description, alpn, IrohStreamBridge(bi))
                        } catch {}
                    }
                } catch { break }
            }
        }
    }
}

private func registerIrohTransport() {
    IrohSwiftRegistry.shared.impl = IrohTransportBridge()
}
#else
private func registerIrohTransport() {
    // No IrohLib — Kotlin falls back to the in-app demo transport.
}
#endif

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> some UIViewController {
        registerIrohTransport()
        return ViewControllerKt.ViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewControllerType, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
            .ignoresSafeArea(.keyboard)
            // Universal links (https://azula.app/s/<token>) and the azula:// scheme.
            .onOpenURL { url in
                DeepLinkKt.handleDeepLink(url: url.absoluteString)
            }
    }
}

@main
struct iosApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
