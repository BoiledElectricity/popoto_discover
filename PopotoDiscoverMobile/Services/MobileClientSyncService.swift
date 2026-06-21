import Foundation

@MainActor
final class MobileClientSyncService {
    struct Progress {
        let step: Int
        let totalSteps: Int
        let message: String
    }

    private struct PayloadFile {
        let resourcePath: String
        let remotePath: String
        let mode: String
    }

    private let deviceManager: DeviceManager
    private let stageRoot = "/tmp/popoto_discover_mobile_sync"
    private let installRoot = "/opt/popoto/popoto_discover"
    private let chunkSize = 1200

    init(deviceManager: DeviceManager = .shared) {
        self.deviceManager = deviceManager
    }

    func sync(progress: @escaping (Progress) -> Void = { _ in }) async -> (success: Bool, message: String) {
        let files = payloadFiles()
        let totalSteps = 3 + files.count * 3
        var step = 0

        func report(_ message: String) {
            step += 1
            progress(Progress(step: step, totalSteps: totalSteps, message: message))
        }

        report("Preparing target")
        var result = await run("rm -rf \(shellQuote(stageRoot)); install -d -m 0755 \(shellQuote(stageRoot))/client \(shellQuote(stageRoot))/common")
        guard result.success else { return result }

        for file in files {
            let staged = "\(stageRoot)/\(file.remotePath)"
            let stagedB64 = "\(staged).b64"
            guard let data = loadResource(path: file.resourcePath) else {
                return (false, "Bundled client resource is missing: \(file.resourcePath)")
            }

            report("Staging \(file.remotePath)")
            result = await run(": > \(shellQuote(stagedB64))")
            guard result.success else { return result }

            let encoded = data.base64EncodedString()
            var index = encoded.startIndex
            while index < encoded.endIndex {
                let next = encoded.index(index, offsetBy: chunkSize, limitedBy: encoded.endIndex) ?? encoded.endIndex
                let chunk = String(encoded[index..<next])
                result = await run("printf '%s' \(shellQuote(chunk)) >> \(shellQuote(stagedB64))", timeout: 10)
                guard result.success else { return result }
                index = next
            }

            report("Installing staged \(file.remotePath)")
            result = await run("base64 -d \(shellQuote(stagedB64)) > \(shellQuote(staged)); chmod \(file.mode) \(shellQuote(staged)); rm -f \(shellQuote(stagedB64))", timeout: 20)
            guard result.success else { return result }
        }

        report("Copying client into place")
        result = await run(copyIntoPlaceCommand(), timeout: 20)
        guard result.success else { return result }

        report("Verifying Python payload")
        result = await run("python3 -m py_compile \(shellQuote(installRoot))/client/popoto_discover_client.py \(shellQuote(installRoot))/common/protocol.py \(shellQuote(installRoot))/common/l2_transport.py", timeout: 20)
        guard result.success else { return result }

        report("Restarting discovery service")
        result = await run("systemctl daemon-reload && systemctl enable popoto-discover.service >/dev/null 2>&1 && (systemctl restart popoto-discover.service >/tmp/popoto-discover-mobile-sync.log 2>&1 &)", timeout: 10)
        if !result.success {
            return result
        }

        return (true, "Popoto Discover client synced")
    }

    private func payloadFiles() -> [PayloadFile] {
        [
            PayloadFile(resourcePath: "modem-client/client/popoto_discover_client.py", remotePath: "client/popoto_discover_client.py", mode: "0755"),
            PayloadFile(resourcePath: "modem-client/client/.popoto_secret", remotePath: "client/.popoto_secret", mode: "0600"),
            PayloadFile(resourcePath: "modem-client/common/__init__.py", remotePath: "common/__init__.py", mode: "0644"),
            PayloadFile(resourcePath: "modem-client/common/l2_transport.py", remotePath: "common/l2_transport.py", mode: "0644"),
            PayloadFile(resourcePath: "modem-client/common/protocol.py", remotePath: "common/protocol.py", mode: "0644"),
            PayloadFile(resourcePath: "modem-client/popoto-discover.service", remotePath: "popoto-discover.service", mode: "0644"),
        ]
    }

    private func loadResource(path: String) -> Data? {
        let candidates = [
            Bundle.main.resourceURL?.appendingPathComponent(path),
            Bundle.main.resourceURL?.appendingPathComponent("BundledClient").appendingPathComponent(path),
        ]

        for candidate in candidates.compactMap({ $0 }) where FileManager.default.isReadableFile(atPath: candidate.path) {
            return try? Data(contentsOf: candidate)
        }
        return nil
    }

    private func copyIntoPlaceCommand() -> String {
        [
            "BASE=\(shellQuote(installRoot))",
            "STAGE=\(shellQuote(stageRoot))",
            "install -d -m 0755 \"$BASE/client\" \"$BASE/common\"",
            "install -m 0755 \"$STAGE/client/popoto_discover_client.py\" \"$BASE/client/popoto_discover_client.py\"",
            "install -m 0600 \"$STAGE/client/.popoto_secret\" \"$BASE/client/.popoto_secret\"",
            "install -m 0644 \"$STAGE/common/__init__.py\" \"$BASE/common/__init__.py\"",
            "install -m 0644 \"$STAGE/common/l2_transport.py\" \"$BASE/common/l2_transport.py\"",
            "install -m 0644 \"$STAGE/common/protocol.py\" \"$BASE/common/protocol.py\"",
            "install -m 0644 \"$STAGE/popoto-discover.service\" /etc/systemd/system/popoto-discover.service",
        ].joined(separator: "; ")
    }

    private func run(_ command: String, timeout: TimeInterval = 15) async -> (success: Bool, message: String) {
        await deviceManager.shellExec(command, timeout: timeout)
    }

    private func shellQuote(_ value: String) -> String {
        "'\(value.replacingOccurrences(of: "'", with: "'\"'\"'"))'"
    }
}
