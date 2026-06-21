import Foundation
import Darwin

class UDPService: @unchecked Sendable {
    private enum PendingContinuation {
        case discover(CheckedContinuation<Void, Never>)
        case status(CheckedContinuation<(success: Bool, message: String), Never>)
        case rtc(CheckedContinuation<(success: Bool, rtc: String?, message: String), Never>)
    }

    static let shared = UDPService()

    private let port: UInt16 = 33333
    private let broadcastAddress = "255.255.255.255"
    private var socket: Int32 = -1
    private var receiveThread: Thread?
    private var isRunning = false
    private let stateQueue = DispatchQueue(label: "com.popoto.udp.state")
    private let continuationQueue = DispatchQueue(label: "com.popoto.udp.continuations")

    private var pendingContinuations: [String: PendingContinuation] = [:]

    var onSnapshotChanged: ((PortableCoreBridge.SessionSnapshot) -> Void)?

    private init() {}

    var isInitialized: Bool {
        stateQueue.sync {
            isRunning && socket >= 0
        }
    }

    func initialize() throws {
        guard !isInitialized else {
            if let snapshot = PortableCoreBridge.shared.currentSessionSnapshot() {
                emitSnapshot(snapshot)
            }
            return
        }

        let newSocket = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard newSocket >= 0 else {
            throw NSError(domain: "UDPService", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Failed to create socket"
            ])
        }

        var broadcastEnable: Int32 = 1
        guard setsockopt(
            newSocket,
            SOL_SOCKET,
            SO_BROADCAST,
            &broadcastEnable,
            socklen_t(MemoryLayout<Int32>.size)
        ) == 0 else {
            close(newSocket)
            throw NSError(domain: "UDPService", code: 2, userInfo: [
                NSLocalizedDescriptionKey: "Failed to enable broadcast"
            ])
        }

        var reuseEnable: Int32 = 1
        setsockopt(newSocket, SOL_SOCKET, SO_REUSEADDR, &reuseEnable, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(newSocket, SOL_SOCKET, SO_REUSEPORT, &reuseEnable, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY.bigEndian

        let bindResult = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPtr in
                bind(newSocket, sockaddrPtr, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        guard bindResult == 0 else {
            close(newSocket)
            throw NSError(domain: "UDPService", code: 3, userInfo: [
                NSLocalizedDescriptionKey: "Failed to bind to port \(port)"
            ])
        }

        let thread = Thread { [weak self] in
            self?.receiveLoop(socket: newSocket)
        }

        stateQueue.sync {
            socket = newSocket
            isRunning = true
            receiveThread = thread
        }

        thread.start()

        if let mutation = PortableCoreBridge.shared.initializeSession() {
            applyMutation(mutation)
        }
    }

    func shutdown() {
        let socketToClose: Int32 = stateQueue.sync {
            let currentSocket = socket
            socket = -1
            isRunning = false
            receiveThread?.cancel()
            receiveThread = nil
            return currentSocket
        }

        if socketToClose >= 0 {
            close(socketToClose)
        }

        resumeAllPendingForShutdown()

        if let mutation = PortableCoreBridge.shared.shutdownSession() {
            applyMutation(mutation)
        }
    }

    func selectDevice(uniqueKey: String?) {
        guard let mutation = PortableCoreBridge.shared.selectDevice(uniqueKey: uniqueKey) else {
            return
        }

        applyMutation(mutation)
    }

    func clearLogs() {
        guard let mutation = PortableCoreBridge.shared.clearLogs() else {
            return
        }

        applyMutation(mutation)
    }

    func discoverDevices(timeout: TimeInterval = 5.0) async {
        guard isInitialized,
              let mutation = PortableCoreBridge.shared.startDiscovery(
                timeout: timeout,
                secret: AuthService.shared.requestSecret(),
                replyBroadcast: true
              ) else {
            return
        }

        if let immediateResult = immediateDiscoverCompletion(from: mutation) {
            applyMutation(mutation)
            _ = immediateResult
            return
        }

        guard let nonce = mutation.outboundPackets.first?.nonce else {
            applyMutation(mutation)
            return
        }

        await withCheckedContinuation { continuation in
            storeContinuation(.discover(continuation), for: nonce)
            applyMutation(mutation)
        }
    }

    func setIpAddress(
        newIp: String,
        netmask: String,
        gateway: String,
        timeout: TimeInterval = 5.0
    ) async -> (success: Bool, message: String) {
        guard isInitialized,
              let mutation = PortableCoreBridge.shared.startSetIp(
                newIp: newIp,
                netmask: netmask,
                gateway: gateway,
                timeout: timeout,
                secret: AuthService.shared.requestSecret()
              ) else {
            return (false, "Service is not connected")
        }

        if let immediate = immediateStatusCompletion(from: mutation) {
            applyMutation(mutation)
            return immediate
        }

        guard let nonce = mutation.outboundPackets.first?.nonce else {
            applyMutation(mutation)
            return (false, "Failed to start request")
        }

        return await withCheckedContinuation { continuation in
            storeContinuation(.status(continuation), for: nonce)
            applyMutation(mutation)
        }
    }

    func setRtc(
        rtcString: String,
        timeout: TimeInterval = 5.0
    ) async -> (success: Bool, message: String) {
        guard isInitialized,
              let mutation = PortableCoreBridge.shared.startSetRtc(
                rtc: rtcString,
                timeout: timeout,
                secret: AuthService.shared.requestSecret()
              ) else {
            return (false, "Service is not connected")
        }

        if let immediate = immediateStatusCompletion(from: mutation) {
            applyMutation(mutation)
            return immediate
        }

        guard let nonce = mutation.outboundPackets.first?.nonce else {
            applyMutation(mutation)
            return (false, "Failed to start request")
        }

        return await withCheckedContinuation { continuation in
            storeContinuation(.status(continuation), for: nonce)
            applyMutation(mutation)
        }
    }

    func getRtc(timeout: TimeInterval = 5.0) async -> (success: Bool, rtc: String?, message: String) {
        guard isInitialized,
              let mutation = PortableCoreBridge.shared.startGetRtc(
                timeout: timeout,
                secret: AuthService.shared.requestSecret()
              ) else {
            return (false, nil, "Service is not connected")
        }

        if let immediate = immediateRtcCompletion(from: mutation) {
            applyMutation(mutation)
            return immediate
        }

        guard let nonce = mutation.outboundPackets.first?.nonce else {
            applyMutation(mutation)
            return (false, nil, "Failed to start request")
        }

        return await withCheckedContinuation { continuation in
            storeContinuation(.rtc(continuation), for: nonce)
            applyMutation(mutation)
        }
    }

    func setParameter(
        paramName: String,
        paramValue: Any,
        timeout: TimeInterval = 5.0
    ) async -> (success: Bool, message: String) {
        guard isInitialized,
              let mutation = PortableCoreBridge.shared.startSetParam(
                paramName: paramName,
                paramValue: paramValue,
                timeout: timeout,
                secret: AuthService.shared.requestSecret()
              ) else {
            return (false, "Service is not connected")
        }

        if let immediate = immediateStatusCompletion(from: mutation) {
            applyMutation(mutation)
            return immediate
        }

        guard let nonce = mutation.outboundPackets.first?.nonce else {
            applyMutation(mutation)
            return (false, "Failed to start request")
        }

        return await withCheckedContinuation { continuation in
            storeContinuation(.status(continuation), for: nonce)
            applyMutation(mutation)
        }
    }

    func shellExec(
        command: String,
        timeout: TimeInterval = 10.0
    ) async -> (success: Bool, message: String) {
        guard isInitialized,
              let mutation = PortableCoreBridge.shared.startShellExec(
                command: command,
                timeout: timeout,
                secret: AuthService.shared.requestSecret()
              ) else {
            return (false, "Service is not connected")
        }

        if let immediate = immediateStatusCompletion(from: mutation) {
            applyMutation(mutation)
            return immediate
        }

        guard let nonce = mutation.outboundPackets.first?.nonce else {
            applyMutation(mutation)
            return (false, "Failed to start request")
        }

        return await withCheckedContinuation { continuation in
            storeContinuation(.status(continuation), for: nonce)
            applyMutation(mutation)
        }
    }

    private func receiveLoop(socket receiveSocket: Int32) {
        var buffer = [UInt8](repeating: 0, count: 65536)

        while shouldKeepReceiving(on: receiveSocket) {
            var senderAddr = sockaddr_in()
            var senderAddrLen = socklen_t(MemoryLayout<sockaddr_in>.size)

            let bytesRead = withUnsafeMutablePointer(to: &senderAddr) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPtr in
                    recvfrom(receiveSocket, &buffer, buffer.count, 0, sockaddrPtr, &senderAddrLen)
                }
            }

            if bytesRead > 0 {
                let data = Data(bytes: buffer, count: bytesRead)
                processReceivedData(data)
            } else if bytesRead < 0, !shouldKeepReceiving(on: receiveSocket) {
                break
            }
        }
    }

    private func processReceivedData(_ data: Data) {
        guard let mutation = PortableCoreBridge.shared.handleIncomingPacket(
            data,
            secret: AuthService.shared.requestSecret()
        ) else {
            return
        }

        applyMutation(mutation)
    }

    private func applyMutation(_ mutation: PortableCoreBridge.SessionMutation) {
        emitSnapshot(mutation.snapshot)

        for outboundPacket in mutation.outboundPackets {
            sendBroadcast(data: outboundPacket.payload)
            scheduleTimeout(for: outboundPacket)
        }

        for completedOperation in mutation.completedOperations {
            handleCompletedOperation(completedOperation)
        }
    }

    private func emitSnapshot(_ snapshot: PortableCoreBridge.SessionSnapshot) {
        DispatchQueue.main.async {
            self.onSnapshotChanged?(snapshot)
        }
    }

    private func handleCompletedOperation(_ operation: PortableCoreBridge.CompletedOperation) {
        if operation.success,
           operation.type == "set_ip",
           let rediscoverDelay = operation.rediscoverDelay {
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(rediscoverDelay * 1_000_000_000))
                guard let self, self.isInitialized else { return }
                await self.discoverDevices()
            }
        }

        guard !operation.nonce.isEmpty else {
            return
        }

        let continuation = removeContinuation(for: operation.nonce)

        switch continuation {
        case .discover(let continuation):
            continuation.resume()
        case .status(let continuation):
            continuation.resume(returning: (operation.success, operation.message))
        case .rtc(let continuation):
            continuation.resume(returning: (operation.success, operation.rtc, operation.message))
        case nil:
            break
        }
    }

    private func scheduleTimeout(for outboundPacket: PortableCoreBridge.OutboundPacket) {
        DispatchQueue.global().asyncAfter(deadline: .now() + outboundPacket.timeout) { [weak self] in
            guard let self,
                  let mutation = PortableCoreBridge.shared.handleTimeout(nonce: outboundPacket.nonce) else {
                return
            }

            self.applyMutation(mutation)
        }
    }

    private func sendBroadcast(data: Data) {
        let activeSocket = stateQueue.sync { socket }
        guard activeSocket >= 0 else { return }

        for targetAddress in broadcastTargets() {
            var destAddr = sockaddr_in()
            destAddr.sin_family = sa_family_t(AF_INET)
            destAddr.sin_port = port.bigEndian
            destAddr.sin_addr.s_addr = targetAddress

            _ = data.withUnsafeBytes { ptr in
                withUnsafePointer(to: &destAddr) { addrPtr in
                    addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPtr in
                        sendto(activeSocket, ptr.baseAddress, data.count, 0, sockaddrPtr, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        }
    }

    private func broadcastTargets() -> [in_addr_t] {
        var targets: [in_addr_t] = [inet_addr(broadcastAddress)]

        for address in interfaceBroadcastAddresses() where !targets.contains(address) {
            targets.append(address)
        }

        return targets
    }

    private func interfaceBroadcastAddresses() -> [in_addr_t] {
        var addresses: [in_addr_t] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?

        guard getifaddrs(&ifaddr) == 0, let firstAddress = ifaddr else {
            return addresses
        }

        defer { freeifaddrs(ifaddr) }

        var cursor: UnsafeMutablePointer<ifaddrs>? = firstAddress
        while let current = cursor {
            let interface = current.pointee
            cursor = interface.ifa_next

            guard let address = interface.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET),
                  let broadcast = interface.ifa_dstaddr else {
                continue
            }

            let flags = interface.ifa_flags
            guard (flags & UInt32(IFF_UP)) != 0,
                  (flags & UInt32(IFF_BROADCAST)) != 0,
                  (flags & UInt32(IFF_LOOPBACK)) == 0 else {
                continue
            }

            let broadcastAddress = broadcast.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { ptr in
                ptr.pointee.sin_addr.s_addr
            }

            if broadcastAddress != 0 {
                addresses.append(broadcastAddress)
            }
        }

        return addresses
    }

    private func storeContinuation(_ continuation: PendingContinuation, for nonce: String) {
        continuationQueue.sync {
            pendingContinuations[nonce] = continuation
        }
    }

    private func removeContinuation(for nonce: String) -> PendingContinuation? {
        continuationQueue.sync {
            pendingContinuations.removeValue(forKey: nonce)
        }
    }

    private func resumeAllPendingForShutdown() {
        let continuations: [PendingContinuation] = continuationQueue.sync {
            let current = Array(pendingContinuations.values)
            pendingContinuations.removeAll()
            return current
        }

        for continuation in continuations {
            switch continuation {
            case .discover(let continuation):
                continuation.resume()
            case .status(let continuation):
                continuation.resume(returning: (false, "Service stopped"))
            case .rtc(let continuation):
                continuation.resume(returning: (false, nil, "Service stopped"))
            }
        }
    }

    private func immediateDiscoverCompletion(from mutation: PortableCoreBridge.SessionMutation) -> Bool? {
        guard mutation.outboundPackets.isEmpty,
              let completion = mutation.completedOperations.first(where: { $0.type == "discover" }) else {
            return nil
        }

        return completion.success
    }

    private func immediateStatusCompletion(from mutation: PortableCoreBridge.SessionMutation) -> (success: Bool, message: String)? {
        guard mutation.outboundPackets.isEmpty,
              let completion = mutation.completedOperations.first else {
            return nil
        }

        return (completion.success, completion.message)
    }

    private func immediateRtcCompletion(from mutation: PortableCoreBridge.SessionMutation) -> (success: Bool, rtc: String?, message: String)? {
        guard mutation.outboundPackets.isEmpty,
              let completion = mutation.completedOperations.first else {
            return nil
        }

        return (completion.success, completion.rtc, completion.message)
    }

    private func shouldKeepReceiving(on receiveSocket: Int32) -> Bool {
        guard !Thread.current.isCancelled else { return false }

        return stateQueue.sync {
            isRunning && socket == receiveSocket
        }
    }
}
