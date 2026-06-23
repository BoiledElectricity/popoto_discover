"""
Raw Ethernet transport for Popoto discovery.

This transport carries the same authenticated JSON messages as the UDP
discovery protocol, but below IP. It can discover devices on the same Ethernet
broadcast domain even when their IPv4 address, subnet, or gateway is wrong.
"""

from __future__ import annotations

import ctypes
import ctypes.util
import errno
import fcntl
import json
import os
import platform
import re
import select
import socket
import struct
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional


ETH_ALEN = 6
ETH_HLEN = 14
ETH_ZLEN = 60
ETH_BROADCAST = b"\xff" * ETH_ALEN

# Locally used EtherType for Popoto discovery JSON frames.
ETH_P_POPOTO_DISCOVERY = 0x88B6

POPOTO_L2_MAGIC = b"PDSC"
POPOTO_L2_VERSION = 1
POPOTO_L2_JSON = 1
POPOTO_L2_HEADER = struct.Struct("!4sBBH")
POPOTO_L2_MAX_JSON = 1400


class L2TransportError(RuntimeError):
    """Raised when raw Ethernet discovery cannot be opened or used."""


@dataclass
class L2Packet:
    src: bytes
    dst: bytes
    message: Dict[str, Any]
    interface: str


class PcapTimeval(ctypes.Structure):
    _fields_ = [
        ("tv_sec", ctypes.c_long),
        ("tv_usec", ctypes.c_int),
    ]


class PcapPkthdr(ctypes.Structure):
    _fields_ = [
        ("ts", PcapTimeval),
        ("caplen", ctypes.c_uint32),
        ("len", ctypes.c_uint32),
    ]


class PcapBpfProgram(ctypes.Structure):
    _fields_ = [
        ("bf_len", ctypes.c_uint32),
        ("bf_insns", ctypes.c_void_p),
    ]


def mac_to_text(mac: bytes) -> str:
    return ":".join("{:02x}".format(b) for b in mac)


def parse_mac(text: str) -> bytes:
    parts = text.split(":")
    if len(parts) != ETH_ALEN:
        raise ValueError("invalid MAC address: {}".format(text))
    return bytes(int(p, 16) for p in parts)


def candidate_interfaces() -> List[str]:
    system = platform.system()
    if system == "Linux":
        names = []
        for path in sorted(Path("/sys/class/net").iterdir()):
            name = path.name
            if name == "lo":
                continue
            if name.startswith(("br-", "docker", "dummy", "uplink", "veth", "virbr", "tailscale", "wt")):
                continue
            try:
                operstate = (path / "operstate").read_text(encoding="ascii").strip()
            except OSError:
                operstate = "unknown"
            if operstate not in ("up", "unknown"):
                continue
            try:
                if (path / "type").read_text(encoding="ascii").strip() != "1":
                    continue
            except OSError:
                continue
            addr_path = path / "address"
            if not addr_path.exists():
                continue
            try:
                mac = addr_path.read_text(encoding="ascii").strip()
            except OSError:
                continue
            if mac in ("", "00:00:00:00:00:00"):
                continue
            names.append(name)
        return sorted(names, key=lambda item: (item != "eth0", item))

    if system == "Darwin":
        try:
            output = subprocess.check_output(["ifconfig", "-l"], text=True)
            names = []
            for name in output.split():
                if name == "lo0" or name.startswith(("awdl", "llw", "utun", "bridge", "gif", "stf")):
                    continue
                try:
                    ifconfig = subprocess.check_output(["ifconfig", name], text=True)
                except Exception:
                    continue
                if "status: active" not in ifconfig and "UP," not in ifconfig:
                    continue
                if "ether " not in ifconfig:
                    continue
                names.append(name)
            return names
        except Exception:
            return []

    return []


def build_json_frame(dst: bytes, src: bytes, message: Dict[str, Any]) -> bytes:
    data = json.dumps(message, separators=(",", ":")).encode("utf-8")
    if len(data) > POPOTO_L2_MAX_JSON:
        raise L2TransportError("L2 discovery message too large: {} bytes".format(len(data)))
    payload = POPOTO_L2_HEADER.pack(POPOTO_L2_MAGIC, POPOTO_L2_VERSION, POPOTO_L2_JSON, len(data)) + data
    frame = dst + src + struct.pack("!H", ETH_P_POPOTO_DISCOVERY) + payload
    if len(frame) < ETH_ZLEN:
        frame += b"\0" * (ETH_ZLEN - len(frame))
    return frame


def parse_json_frame(frame: bytes, interface: str) -> Optional[L2Packet]:
    if len(frame) < ETH_HLEN + POPOTO_L2_HEADER.size:
        return None
    if struct.unpack("!H", frame[12:14])[0] != ETH_P_POPOTO_DISCOVERY:
        return None

    payload = frame[ETH_HLEN:]
    magic, version, payload_type, json_len = POPOTO_L2_HEADER.unpack(payload[: POPOTO_L2_HEADER.size])
    if magic != POPOTO_L2_MAGIC or version != POPOTO_L2_VERSION or payload_type != POPOTO_L2_JSON:
        return None

    start = POPOTO_L2_HEADER.size
    end = start + json_len
    if end > len(payload):
        return None

    try:
        message = json.loads(payload[start:end].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None

    return L2Packet(src=frame[6:12], dst=frame[:6], message=message, interface=interface)


class EthernetTransport:
    def __init__(self, interface: str, timeout: float) -> None:
        self.interface = interface
        self.timeout = timeout
        self.local_mac = b""

    def fileno(self) -> Optional[int]:
        return None

    def send(self, frame: bytes) -> None:
        raise NotImplementedError

    def recv(self, timeout: float) -> Optional[bytes]:
        raise NotImplementedError

    def send_json(self, dst: bytes, message: Dict[str, Any]) -> None:
        self.send(build_json_frame(dst, self.local_mac, message))

    def recv_json(self, timeout: float) -> Optional[L2Packet]:
        frame = self.recv(timeout)
        if frame is None:
            return None
        return parse_json_frame(frame, self.interface)

    def close(self) -> None:
        pass


class LinuxPacketTransport(EthernetTransport):
    def __init__(self, interface: str, timeout: float) -> None:
        super().__init__(interface, timeout)
        self.local_mac = parse_mac(Path("/sys/class/net/{}/address".format(interface)).read_text().strip())
        self.sock = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(ETH_P_POPOTO_DISCOVERY))
        self.sock.bind((interface, ETH_P_POPOTO_DISCOVERY))
        self.sock.setblocking(False)

    def fileno(self) -> int:
        return self.sock.fileno()

    def send(self, frame: bytes) -> None:
        self.sock.send(frame)

    def recv(self, timeout: float) -> Optional[bytes]:
        readable, _, _ = select.select([self.sock], [], [], timeout)
        if not readable:
            return None
        try:
            frame = self.sock.recv(65535)
        except OSError as exc:
            raise L2TransportError("{} receive failed: {}".format(self.interface, exc)) from exc
        if len(frame) >= ETH_HLEN and struct.unpack("!H", frame[12:14])[0] == ETH_P_POPOTO_DISCOVERY:
            return frame
        return None

    def close(self) -> None:
        self.sock.close()


class DarwinPcapTransport(EthernetTransport):
    DLT_EN10MB = 1
    PCAP_ERRBUF_SIZE = 256

    def __init__(self, interface: str, timeout: float) -> None:
        super().__init__(interface, timeout)
        self.local_mac = mac_from_ifconfig(interface)
        self.libpcap = self._load_pcap()
        self.handle = self._open(interface, timeout)

    @classmethod
    def _load_pcap(cls):
        name = ctypes.util.find_library("pcap") or "libpcap.dylib"
        lib = ctypes.CDLL(name)
        lib.pcap_create.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
        lib.pcap_create.restype = ctypes.c_void_p
        lib.pcap_set_snaplen.argtypes = [ctypes.c_void_p, ctypes.c_int]
        lib.pcap_set_promisc.argtypes = [ctypes.c_void_p, ctypes.c_int]
        lib.pcap_set_timeout.argtypes = [ctypes.c_void_p, ctypes.c_int]
        lib.pcap_activate.argtypes = [ctypes.c_void_p]
        lib.pcap_activate.restype = ctypes.c_int
        lib.pcap_datalink.argtypes = [ctypes.c_void_p]
        lib.pcap_datalink.restype = ctypes.c_int
        lib.pcap_setnonblock.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_char_p]
        lib.pcap_setnonblock.restype = ctypes.c_int
        lib.pcap_compile.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(PcapBpfProgram),
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_uint32,
        ]
        lib.pcap_compile.restype = ctypes.c_int
        lib.pcap_setfilter.argtypes = [ctypes.c_void_p, ctypes.POINTER(PcapBpfProgram)]
        lib.pcap_setfilter.restype = ctypes.c_int
        lib.pcap_freecode.argtypes = [ctypes.POINTER(PcapBpfProgram)]
        lib.pcap_next_ex.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.POINTER(PcapPkthdr)),
            ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte)),
        ]
        lib.pcap_next_ex.restype = ctypes.c_int
        lib.pcap_inject.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t]
        lib.pcap_inject.restype = ctypes.c_int
        lib.pcap_geterr.argtypes = [ctypes.c_void_p]
        lib.pcap_geterr.restype = ctypes.c_char_p
        lib.pcap_close.argtypes = [ctypes.c_void_p]
        if hasattr(lib, "pcap_set_immediate_mode"):
            lib.pcap_set_immediate_mode.argtypes = [ctypes.c_void_p, ctypes.c_int]
        return lib

    def _pcap_error(self, prefix: str, errbuf: Optional[ctypes.Array] = None) -> L2TransportError:
        message = ""
        if getattr(self, "handle", None):
            raw = self.libpcap.pcap_geterr(self.handle)
            message = raw.decode(errors="replace") if raw else ""
        elif errbuf is not None:
            message = errbuf.value.decode(errors="replace")
        return L2TransportError("{}: {}".format(prefix, message) if message else prefix)

    def _open(self, interface: str, timeout: float) -> ctypes.c_void_p:
        errbuf = ctypes.create_string_buffer(self.PCAP_ERRBUF_SIZE)
        handle = self.libpcap.pcap_create(interface.encode("ascii"), errbuf)
        if not handle:
            raise self._pcap_error("pcap_create failed", errbuf)
        self.handle = handle
        try:
            self.libpcap.pcap_set_snaplen(handle, 65535)
            self.libpcap.pcap_set_promisc(handle, 1)
            self.libpcap.pcap_set_timeout(handle, max(int(timeout * 1000), 1))
            if hasattr(self.libpcap, "pcap_set_immediate_mode"):
                self.libpcap.pcap_set_immediate_mode(handle, 1)
            rc = self.libpcap.pcap_activate(handle)
            if rc < 0:
                raise self._pcap_error("pcap_activate failed")
            if self.libpcap.pcap_datalink(handle) != self.DLT_EN10MB:
                raise L2TransportError("{} is not an Ethernet pcap interface".format(interface))
            if self.libpcap.pcap_setnonblock(handle, 1, errbuf) != 0:
                raise self._pcap_error("pcap_setnonblock failed", errbuf)
            self._set_filter()
            return handle
        except Exception:
            self.libpcap.pcap_close(handle)
            self.handle = None
            raise

    def _set_filter(self) -> None:
        prog = PcapBpfProgram()
        expr = "ether proto 0x{:04x}".format(ETH_P_POPOTO_DISCOVERY).encode("ascii")
        if self.libpcap.pcap_compile(self.handle, ctypes.byref(prog), expr, 1, 0) != 0:
            raise self._pcap_error("pcap_compile failed")
        try:
            if self.libpcap.pcap_setfilter(self.handle, ctypes.byref(prog)) != 0:
                raise self._pcap_error("pcap_setfilter failed")
        finally:
            self.libpcap.pcap_freecode(ctypes.byref(prog))

    def send(self, frame: bytes) -> None:
        buf = ctypes.create_string_buffer(frame)
        written = self.libpcap.pcap_inject(self.handle, buf, len(frame))
        if written != len(frame):
            raise self._pcap_error("pcap_inject wrote {} of {} bytes".format(written, len(frame)))

    def recv(self, timeout: float) -> Optional[bytes]:
        deadline = time.monotonic() + timeout
        header = ctypes.POINTER(PcapPkthdr)()
        data = ctypes.POINTER(ctypes.c_ubyte)()
        while True:
            rc = self.libpcap.pcap_next_ex(self.handle, ctypes.byref(header), ctypes.byref(data))
            if rc == 1:
                frame = ctypes.string_at(data, header.contents.caplen)
                if len(frame) >= ETH_HLEN and struct.unpack("!H", frame[12:14])[0] == ETH_P_POPOTO_DISCOVERY:
                    return frame
                continue
            if rc < 0:
                raise self._pcap_error("pcap_next_ex failed")
            if timeout <= 0 or time.monotonic() >= deadline:
                return None
            time.sleep(0.001)

    def close(self) -> None:
        if self.handle:
            self.libpcap.pcap_close(self.handle)
            self.handle = None


class DarwinBpfTransport(EthernetTransport):
    IOC_VOID = 0x20000000
    IOC_OUT = 0x40000000
    IOC_IN = 0x80000000
    IOC_INOUT = IOC_IN | IOC_OUT

    @staticmethod
    def _ioc(direction: int, group: str, number: int, length: int) -> int:
        return direction | (length << 16) | (ord(group) << 8) | number

    class _BpfInsn(ctypes.Structure):
        _fields_ = [
            ("code", ctypes.c_ushort),
            ("jt", ctypes.c_ubyte),
            ("jf", ctypes.c_ubyte),
            ("k", ctypes.c_uint32),
        ]

    class _BpfProgram(ctypes.Structure):
        _fields_ = [
            ("bf_len", ctypes.c_uint32),
            ("bf_insns", ctypes.c_void_p),
        ]

    BIOCGBLEN = _ioc.__func__(IOC_OUT, "B", 102, 4)
    BIOCSBLEN = _ioc.__func__(IOC_INOUT, "B", 102, 4)
    BIOCSETF = _ioc.__func__(IOC_IN, "B", 103, ctypes.sizeof(_BpfProgram))
    BIOCFLUSH = _ioc.__func__(IOC_VOID, "B", 104, 0)
    BIOCPROMISC = _ioc.__func__(IOC_VOID, "B", 105, 0)
    BIOCGDLT = _ioc.__func__(IOC_OUT, "B", 106, 4)
    BIOCSETIF = _ioc.__func__(IOC_IN, "B", 108, 32)
    BIOCSRTIMEOUT = _ioc.__func__(IOC_IN, "B", 109, 16)
    BIOCIMMEDIATE = _ioc.__func__(IOC_IN, "B", 112, 4)
    BIOCSHDRCMPLT = _ioc.__func__(IOC_IN, "B", 117, 4)
    BIOCSSEESENT = _ioc.__func__(IOC_IN, "B", 119, 4)
    DLT_EN10MB = 1

    def __init__(self, interface: str, timeout: float) -> None:
        super().__init__(interface, timeout)
        self.local_mac = mac_from_ifconfig(interface)
        self.fd = self._open_bpf()
        os.set_blocking(self.fd, False)
        self._filter_insns = None
        self.buflen = self._attach(interface, timeout)
        self._set_filter()
        self._rx_buffer = b""

    @staticmethod
    def _open_bpf() -> int:
        last_error = None
        for index in range(256):
            try:
                return os.open("/dev/bpf{}".format(index), os.O_RDWR)
            except OSError as exc:
                last_error = exc
                if exc.errno == errno.EBUSY:
                    continue
                if exc.errno in (errno.EACCES, errno.EPERM):
                    raise L2TransportError("BPF requires sudo/root on macOS") from exc
                raise
        raise L2TransportError("no free /dev/bpf device found: {}".format(last_error))

    def _ioctl_uint(self, request: int, value: int = 0, mutate: bool = True) -> int:
        buf = struct.pack("I", value)
        out = fcntl.ioctl(self.fd, request, buf, mutate)
        if isinstance(out, bytes) and len(out) >= 4:
            return struct.unpack("I", out[:4])[0]
        return value

    def _attach(self, interface: str, timeout: float) -> int:
        requested_len = 1024 * 1024
        self._ioctl_uint(self.BIOCSBLEN, requested_len)
        ifreq = interface.encode("ascii")[:15] + b"\0"
        ifreq = ifreq.ljust(32, b"\0")
        fcntl.ioctl(self.fd, self.BIOCSETIF, ifreq)
        dlt = self._ioctl_uint(self.BIOCGDLT)
        if dlt != self.DLT_EN10MB:
            raise L2TransportError("{} is not an Ethernet BPF interface (DLT {})".format(interface, dlt))
        self._ioctl_uint(self.BIOCIMMEDIATE, 1)
        self._ioctl_uint(self.BIOCSHDRCMPLT, 1)
        self._ioctl_uint(self.BIOCSSEESENT, 0)
        sec = int(timeout)
        usec = int((timeout - sec) * 1000000)
        fcntl.ioctl(self.fd, self.BIOCSRTIMEOUT, struct.pack("ll", sec, usec))
        try:
            fcntl.ioctl(self.fd, self.BIOCPROMISC, b"")
        except OSError:
            pass
        fcntl.ioctl(self.fd, self.BIOCFLUSH, b"")
        return self._ioctl_uint(self.BIOCGBLEN)

    def _set_filter(self) -> None:
        insns = (self._BpfInsn * 4)(
            self._BpfInsn(0x28, 0, 0, 12),
            self._BpfInsn(0x15, 0, 1, ETH_P_POPOTO_DISCOVERY),
            self._BpfInsn(0x06, 0, 0, 0xFFFF),
            self._BpfInsn(0x06, 0, 0, 0),
        )
        prog = self._BpfProgram(len(insns), ctypes.addressof(insns))
        fcntl.ioctl(self.fd, self.BIOCSETF, bytes(prog))
        self._filter_insns = insns

    @staticmethod
    def _bpf_align(length: int) -> int:
        return (length + 3) & ~3

    def fileno(self) -> int:
        return self.fd

    def send(self, frame: bytes) -> None:
        os.write(self.fd, frame)

    def recv(self, timeout: float) -> Optional[bytes]:
        deadline = time.monotonic() + timeout
        while True:
            if self._rx_buffer:
                frame = self._pop_frame()
                if frame is not None:
                    return frame
            remaining = max(deadline - time.monotonic(), 0)
            readable, _, _ = select.select([self.fd], [], [], remaining)
            if not readable:
                return None
            try:
                self._rx_buffer += os.read(self.fd, self.buflen)
            except BlockingIOError:
                if timeout <= 0:
                    return None
                continue
            if timeout <= 0 and not self._rx_buffer:
                return None

    def _pop_frame(self) -> Optional[bytes]:
        if len(self._rx_buffer) < 20:
            return None
        _, _, caplen, _, hdrlen = struct.unpack_from("IIIIH", self._rx_buffer, 0)
        total = self._bpf_align(hdrlen + caplen)
        if len(self._rx_buffer) < total:
            return None
        record = self._rx_buffer[:total]
        self._rx_buffer = self._rx_buffer[total:]
        frame = record[hdrlen : hdrlen + caplen]
        if len(frame) >= ETH_HLEN and struct.unpack("!H", frame[12:14])[0] == ETH_P_POPOTO_DISCOVERY:
            return frame
        return None

    def close(self) -> None:
        os.close(self.fd)


def mac_from_ifconfig(interface: str) -> bytes:
    output = subprocess.check_output(["ifconfig", interface], text=True)
    match = re.search(r"\bether\s+([0-9a-fA-F:]{17})", output)
    if not match:
        raise L2TransportError("could not find MAC address for {}".format(interface))
    return parse_mac(match.group(1).lower())


def open_transport(interface: str, timeout: float) -> EthernetTransport:
    system = platform.system()
    if system == "Linux":
        return LinuxPacketTransport(interface, timeout)
    if system == "Darwin":
        try:
            return DarwinPcapTransport(interface, timeout)
        except Exception:
            return DarwinBpfTransport(interface, timeout)
    raise L2TransportError("raw Ethernet discovery is not implemented on {}".format(system))
