import socket
import threading
import time

import os
import json
import shutil
import getpass
from pathlib import Path

VALID_CONNECTION_TYPES = ("serial", "udp")


def get_mergix_config():
    """Load drone bridge configuration.

    Looks for a config file on any mounted external storage first (and
    caches a copy locally), then falls back to the local cache file, then
    to hardcoded defaults.
    """
    # Hardcoded fallback defaults
    defaults = {
        "drone_id": "WWT-5G@1",
        "telem_ip": "2.2.2.2",
        "telem_port": 6666,
        "drone_connection_type": "serial",
        "drone_port": "/dev/ttyAMA0",
        "baud_rate": 115200
    }

    script_dir = Path(__file__).resolve().parent
    local_file = script_dir / "static_mergix_data.json"

    media_base = Path("/media") / getpass.getuser()
    print(f"[INFO] Scanning external storage base path: {media_base}")

    # Check the base path exactly once. No loops, no waiting.
    if media_base.exists():
        try:
            active_mounts = [p for p in media_base.iterdir() if p.is_dir() and not p.name.startswith(".")]
        except Exception as e:
            print(f"[WARNING] Could not list '{media_base}': {e}")
            active_mounts = []

        for mount_point in active_mounts:
            if os.path.ismount(str(mount_point)):
                ext_file = mount_point / "mergix_data.json"

                try:
                    if ext_file.exists():
                        with open(ext_file, "r") as f:
                            json.load(f)  # Integrity check

                        print(f"[INFO] Found external configuration file at: {ext_file}")
                        shutil.copy2(ext_file, local_file)
                        break  # Successfully copied target file, break the loop
                except (json.JSONDecodeError, PermissionError, OSError) as e:
                    print(f"[WARNING] Skipping mount point '{mount_point.name}': {e}")
                    pass

    # --- RESOLVE CONFIGURATION FROM LOCAL CACHE ---
    if local_file.exists():
        try:
            with open(local_file, "r") as f:
                data = json.load(f)
            print("[INFO] Successfully loaded configuration from local static cache.")

            config_out = {k: data.get(k, defaults[k]) for k in defaults}
            config_out["video_input_link"] = data.get("video_input_link", None)
            config_out["video_output_link"] = data.get("video_output_link", None)
            config_out["video_res"] = data.get("video_res", None)
            return _normalize_config(config_out)
        except Exception as e:
            print(f"[WARNING] Local cache file unreadable: {e}")

    config_defaults = defaults.copy()
    config_defaults["video_input_link"] = None
    config_defaults["video_output_link"] = None
    config_defaults["video_res"] = None
    print("[WARNING] Falling back to hardcoded default configurations.")
    return _normalize_config(config_defaults)


def _normalize_config(config):
    """Validate/clean up fields the rest of the program depends on, instead
    of letting a bad or missing value blow up somewhere deep in a thread."""
    conn_type = str(config.get("drone_connection_type", "serial")).strip().lower()
    if conn_type not in VALID_CONNECTION_TYPES:
        print(f"[WARNING] Unknown drone_connection_type '{config.get('drone_connection_type')}', "
              f"defaulting to 'serial'")
        conn_type = "serial"
    config["drone_connection_type"] = conn_type

    if not config.get("drone_port"):
        if conn_type == "udp":
            # No safe hardcoded default exists for a UDP host:port, so rather
            # than crash later in parse_host_port(), fall all the way back
            # to serial — the one transport we always have a sane default for.
            print("[WARNING] drone_connection_type is 'udp' but drone_port is missing — "
                  "falling back to serial on '/dev/ttyAMA0'")
            config["drone_connection_type"] = "serial"
            config["drone_port"] = "/dev/ttyAMA0"
        else:
            config["drone_port"] = "/dev/ttyAMA0"
            print(f"[WARNING] drone_port missing, defaulting to '{config['drone_port']}'")

    if config["drone_connection_type"] == "udp":
        try:
            parse_host_port(config["drone_port"])
        except ValueError as e:
            print(f"[WARNING] drone_port '{config['drone_port']}' invalid for udp mode ({e}) — "
                  f"falling back to serial on '/dev/ttyAMA0'")
            config["drone_connection_type"] = "serial"
            config["drone_port"] = "/dev/ttyAMA0"

    try:
        config["baud_rate"] = int(config.get("baud_rate", 115200))
    except (TypeError, ValueError):
        print(f"[WARNING] Invalid baud_rate '{config.get('baud_rate')}', defaulting to 115200")
        config["baud_rate"] = 115200

    try:
        config["telem_port"] = int(config.get("telem_port", 6666))
    except (TypeError, ValueError):
        print(f"[WARNING] Invalid telem_port '{config.get('telem_port')}', defaulting to 6666")
        config["telem_port"] = 6666

    return config


def parse_host_port(value):
    """Parse a 'host:port' string into (host, port). Raises ValueError if malformed."""
    if not value or not isinstance(value, str):
        raise ValueError("expected a non-empty 'host:port' string")
    value = value.strip()
    if ":" not in value:
        raise ValueError(f"'{value}' is not in 'host:port' format")
    host, _, port_str = value.rpartition(":")
    if not host:
        raise ValueError(f"'{value}' is missing a host")
    try:
        port = int(port_str)
    except ValueError:
        raise ValueError(f"'{value}' has a non-numeric port '{port_str}'")
    if not (0 < port < 65536):
        raise ValueError(f"'{value}' port {port} is out of range")
    return host, port


# ---------- Configuration (populated from get_mergix_config() in __main__) ----------
DRONE_ID              = "WWT-5G@1"
DRONE_CONNECTION_TYPE = "serial"       # "serial" or "udp"
DRONE_PORT            = "/dev/ttyAMA0" # a /dev/tty path for serial, or "host:port" for udp
BAUD_RATE             = 115200
TCP_HOST              = "2.2.2.2"
TCP_PORT              = 6666
BUFFER_SIZE           = 4096
RECONNECT_DELAY       = 3
HEARTBEAT_TIMEOUT     = 5    # seconds to wait for echo in handshake
BRIDGE_WATCHDOG       = 5    # seconds without 0xFD/0xFE before bridge dies

# Self-healing is only allowed for known, expected I/O failures (OSError and
# its subclasses — unplugged device, dropped socket, etc.), and only up to
# this many times within this many seconds. Past that budget, or for any
# exception type outside OSError, the error is treated as unaccounted-for
# and is left to propagate and crash the process for systemd to restart.
MAX_RECOVERY_ATTEMPTS = 5
RECOVERY_WINDOW       = 60   # seconds
# --------------------------------------------------------------------------------

running = True


def _within_recovery_budget(attempt_timestamps, label):
    """Keeps a rolling window of recent self-heal attempts for one link and
    enforces a bounded budget. Returns True (and records this attempt) if
    still within budget; returns False if the budget is exhausted, meaning
    the caller should stop self-healing and let the error escalate instead."""
    now = time.time()
    while attempt_timestamps and now - attempt_timestamps[0] > RECOVERY_WINDOW:
        attempt_timestamps.pop(0)
    if len(attempt_timestamps) >= MAX_RECOVERY_ATTEMPTS:
        print(f"[{label}] {MAX_RECOVERY_ATTEMPTS} recovery attempts within {RECOVERY_WINDOW}s — "
              f"this isn't a brief blip, giving up self-healing and letting it crash")
        return False
    attempt_timestamps.append(now)
    return True


def _thread_excepthook(args):
    """Any bridge thread that dies from an unhandled exception takes the
    whole process down with it. Recovery is systemd's job (Restart=always),
    not ours — no internal retry logic left in this script."""
    print(f"[FATAL] Unhandled exception in thread '{args.thread.name}': "
          f"{args.exc_type.__name__}: {args.exc_value}")
    import traceback
    traceback.print_exception(args.exc_type, args.exc_value, args.exc_traceback)
    os._exit(1)


threading.excepthook = _thread_excepthook


# ---------- Drone-side transport abstraction ----------
# SerialLink and UDPLink both expose read_available() / write() / close(),
# so bridge() below doesn't need to know or care which one it's using.

class SerialLink:
    """Wraps a pyserial connection. Self-heals (bounded) from OSError-type
    failures — e.g. the USB-serial adapter or flight controller briefly
    drops out — by reopening the port. Any other exception type, or an
    OSError recurring past the recovery budget, propagates uncaught so the
    process crashes and systemd restarts it fresh."""

    def __init__(self, port, baud):
        self._port = port
        self._baud = baud
        self._ser = None
        self._recovery_attempts = []
        self._open_with_recovery()

    def _open(self):
        import serial  # imported lazily so pyserial isn't required in UDP-only setups
        self._ser = serial.Serial(self._port, self._baud, timeout=None)
        print(f"[Serial] Opened {self._port} @ {self._baud} baud")

    def _open_with_recovery(self):
        while True:
            try:
                self._open()
                return
            except OSError as e:
                if not _within_recovery_budget(self._recovery_attempts, "Serial"):
                    raise
                print(f"[Serial] Could not open {self._port} ({e}) — "
                      f"self-healing: retrying in {RECONNECT_DELAY}s")
                time.sleep(RECONNECT_DELAY)

    def _recover(self, err):
        if not _within_recovery_budget(self._recovery_attempts, "Serial"):
            raise err
        try:
            if self._ser:
                self._ser.close()
        except Exception:
            pass
        print(f"[Serial] I/O error ({err}) — self-healing: reopening port")
        time.sleep(RECONNECT_DELAY)
        self._open_with_recovery()

    def read_available(self):
        """Returns whatever bytes are currently available. OSError (device
        gone, etc.) triggers bounded self-healing; any other exception type
        propagates and crashes the process."""
        try:
            n = self._ser.in_waiting or 1
            return self._ser.read(n)
        except OSError as e:
            self._recover(e)
            return b""

    def write(self, data):
        try:
            self._ser.write(data)
        except OSError as e:
            self._recover(e)

    def close(self):
        try:
            if self._ser:
                self._ser.close()
        except Exception:
            pass

    def interrupt(self):
        """Forcibly cancels an in-progress blocking read from another thread
        (requires pyserial >= 3.0; silently does nothing on older versions)."""
        try:
            if self._ser and hasattr(self._ser, "cancel_read"):
                self._ser.cancel_read()
        except Exception:
            pass


class UDPLink:
    """UDP endpoint for the drone side. Sends to a configured host:port and
    learns the sender's actual address from the first packet received, so
    replies go back to wherever the traffic is really coming from (handy
    when the peer is behind NAT or using an ephemeral source port). Self-heals
    (bounded) from OSError-type socket failures the same way SerialLink does."""

    def __init__(self, host, port):
        self._target = (host, port)
        self._learned_addr = None
        self._sock = None
        self._recovery_attempts = []
        self._open_with_recovery()

    def _open(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.settimeout(1.0)
        try:
            # Bind locally on the same port so traffic sent to host:port
            # from anywhere on the network reaches us.
            self._sock.bind(("0.0.0.0", self._target[1]))
        except OSError as e:
            print(f"[UDP] Could not bind local port {self._target[1]} ({e}), using an ephemeral port")
            self._sock.bind(("0.0.0.0", 0))
        print(f"[UDP] Ready, will talk to {self._target[0]}:{self._target[1]}")

    def _open_with_recovery(self):
        while True:
            try:
                self._open()
                return
            except OSError as e:
                if not _within_recovery_budget(self._recovery_attempts, "UDP"):
                    raise
                print(f"[UDP] Could not set up socket ({e}) — "
                      f"self-healing: retrying in {RECONNECT_DELAY}s")
                time.sleep(RECONNECT_DELAY)

    def _recover(self, err):
        if not _within_recovery_budget(self._recovery_attempts, "UDP"):
            raise err
        try:
            if self._sock:
                self._sock.close()
        except Exception:
            pass
        print(f"[UDP] I/O error ({err}) — self-healing: reopening socket")
        time.sleep(RECONNECT_DELAY)
        self._open_with_recovery()

    def read_available(self):
        """Returns whatever bytes are currently available. OSError triggers
        bounded self-healing; any other exception type propagates and
        crashes the process."""
        try:
            data, addr = self._sock.recvfrom(BUFFER_SIZE)
            self._learned_addr = addr
            return data
        except socket.timeout:
            return b""
        except OSError as e:
            self._recover(e)
            return b""

    def write(self, data):
        target = self._learned_addr or self._target
        try:
            self._sock.sendto(data, target)
        except OSError as e:
            self._recover(e)

    def close(self):
        try:
            if self._sock:
                self._sock.close()
        except Exception:
            pass

    def interrupt(self):
        # No-op: the UDP socket already uses a short recvfrom() timeout, so it
        # notices a dead session on its own within about a second.
        pass


def build_drone_link(connection_type, drone_port, baud_rate):
    """Factory: builds the correct transport for the configured connection type."""
    if connection_type == "serial":
        return SerialLink(drone_port, baud_rate)
    elif connection_type == "udp":
        host, port = parse_host_port(drone_port)
        return UDPLink(host, port)
    else:
        raise ValueError(f"Unsupported drone_connection_type '{connection_type}' (expected 'serial' or 'udp')")


def heartbeat(conn):
    print("[HB] Starting heartbeat session...")

    # Send our drone ID so the server knows who we are
    conn.sendall(DRONE_ID.encode())
    print("[HB] DRONE_ID sent:", DRONE_ID)
    time.sleep(2)

    # settimeout(N) means: if recv() gets no data within N seconds,
    # stop waiting and raise socket.timeout instead of blocking forever.
    conn.settimeout(HEARTBEAT_TIMEOUT)

    while True:
        # Send a keepalive ping byte to the server every loop
        conn.send(b'\xFD')

        try:
            data = conn.recv(1024)

            if not data:
                # recv() returning empty bytes means the server cleanly closed
                # the TCP connection (sent FIN). Not a timeout — a real disconnect.
                print("[HB] Server closed connection")
                return False

            if b'\xFD' in data:
                print("[HB] Echo received:", data)

            if b"START RELAY" in data:
                print("[HB] Start relay received, entering bridge")
                # Reset timeout to None = blocking mode; the bridge manages
                # its own watchdog, so we don't need the heartbeat timeout.
                conn.settimeout(None)
                return True

        except socket.timeout:
            print(f"[HB] No echo in {HEARTBEAT_TIMEOUT}s — link dead, reconnecting")
            return False

        except OSError as e:
            # Routine connection-level failure (reset, refused, etc.) — self-heal
            # by letting tcp_client() retry the TCP connection from scratch.
            print("[HB] Connection error:", e)
            return False

        time.sleep(1)


def bridge(conn, drone_link):
    print(f"[Bridge] Relay started ({DRONE_CONNECTION_TYPE})")

    # Cleared the moment EITHER side of the bridge ends for a routine, known
    # reason (server disconnect, watchdog timeout, an OSError on the TCP
    # socket). The other thread notices within about a second and returns
    # cleanly, so bridge() ends and tcp_client() reconnects — no full-process
    # crash for something as ordinary as a dropped connection. A genuinely
    # unexpected exception (anything not OSError) still propagates straight
    # out of these threads uncaught, so threading.excepthook crashes the
    # whole process for systemd to restart.
    session_active = threading.Event()
    session_active.set()

    def drone_to_tcp():
        """Reads bytes from the drone (serial or UDP) and forwards to the server."""
        while session_active.is_set():
            data = drone_link.read_available()
            if data:
                try:
                    # sendall() keeps sending until every byte is delivered,
                    # unlike send() which may only send part of the buffer.
                    conn.sendall(data)
                except OSError as e:
                    print(f"[Bridge] TCP send failed ({e}) — ending session, tcp_client() will reconnect")
                    break
        session_active.clear()

    def tcp_to_drone():
        """Reads bytes from server over TCP and forwards to the drone."""

        # Timestamp of the last time we saw a keepalive byte (0xFD or 0xFE).
        last_alive = time.time()

        # settimeout(2) makes recv() give up after 2 seconds if no data
        # arrives, which is what lets the watchdog check run periodically.
        conn.settimeout(2)

        while session_active.is_set():
            try:
                data = conn.recv(BUFFER_SIZE)
            except socket.timeout:
                # Normal path when the link is idle — just check the watchdog.
                if time.time() - last_alive > BRIDGE_WATCHDOG:
                    print(f"[Bridge] No 0xFD/0xFE for {BRIDGE_WATCHDOG}s — ending session, tcp_client() will reconnect")
                    break
                continue
            except OSError as e:
                print(f"[Bridge] TCP recv failed ({e}) — ending session, tcp_client() will reconnect")
                break

            if not data:
                print("[Bridge] Server closed connection — ending session, tcp_client() will reconnect")
                break

            # 0xFD = MAVLink v2 start byte, 0xFE = MAVLink v1 start byte.
            if b'\xFD' in data or b'\xFE' in data:
                last_alive = time.time()

            if time.time() - last_alive > BRIDGE_WATCHDOG:
                print(f"[Bridge] No 0xFD/0xFE for {BRIDGE_WATCHDOG}s — ending session, tcp_client() will reconnect")
                break

            drone_link.write(data)

        session_active.clear()

        # Unblock drone_to_tcp: interrupt() forcibly cancels a blocking serial
        # read (no-op for UDP, which already times out on its own), and
        # shutdown() unblocks it if it's stuck inside conn.sendall().
        drone_link.interrupt()
        try:
            conn.shutdown(socket.SHUT_RDWR)
        except Exception:
            pass

    t1 = threading.Thread(target=drone_to_tcp, daemon=True, name="drone_to_tcp")
    t2 = threading.Thread(target=tcp_to_drone, daemon=True, name="tcp_to_drone")
    t1.start()
    t2.start()

    t1.join()
    t2.join()
    print("[Bridge] Session ended — tcp_client() will reconnect")


def tcp_client(drone_link):
    """Outer loop: keeps trying to connect and run the bridge forever"""
    global running
    while running:
        try:
            print(f"[TCP] Connecting to {TCP_HOST}:{TCP_PORT} ...")
            with socket.create_connection((TCP_HOST, TCP_PORT)) as conn:
                # TCP_NODELAY disables Nagle's algorithm — sends each packet
                # immediately without waiting to batch small writes together.
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

                if heartbeat(conn):
                    bridge(conn, drone_link)

        except (ConnectionRefusedError, OSError) as e:
            print(f"[TCP] Connection failed ({e}), retrying in {RECONNECT_DELAY}s...")
            time.sleep(RECONNECT_DELAY)

        except KeyboardInterrupt:
            break


if __name__ == "__main__":
    drone_link = None
    try:
        config = get_mergix_config()

        DRONE_ID              = config["drone_id"]
        DRONE_CONNECTION_TYPE = config["drone_connection_type"]
        DRONE_PORT            = config["drone_port"]
        BAUD_RATE             = int(config["baud_rate"])
        TCP_HOST              = config["telem_ip"]
        TCP_PORT              = int(config["telem_port"])
        VIDEO_INPUT_LINK      = config["video_input_link"]
        VIDEO_OUTPUT_LINK     = config["video_output_link"]
        VIDEO_RES             = config["video_res"]

        baud_display = str(BAUD_RATE) if DRONE_CONNECTION_TYPE == "serial" else "N/A (UDP)"

        # Print the expanded safe telemetry block
        print("\n" + "="*70)
        print(f"| {'METRIC / VARIABLE':<20} | {'VALUE':<43} |")
        print("="*70)
        print(f"| {'DRONE_ID':<20} | {DRONE_ID:<43} |")
        print(f"| {'CONNECTION_TYPE':<20} | {DRONE_CONNECTION_TYPE:<43} |")
        print(f"| {'DRONE_PORT':<20} | {DRONE_PORT:<43} |")
        print(f"| {'BAUD_RATE':<20} | {baud_display:<43} |")
        print(f"| {'TCP_HOST':<20} | {TCP_HOST:<43} |")
        print(f"| {'TCP_PORT':<20} | {str(TCP_PORT):<43} |")
        print(f"| {'VIDEO_INPUT':<20} | {str(VIDEO_INPUT_LINK):<43} |")
        print(f"| {'VIDEO_OUTPUT':<20} | {str(VIDEO_OUTPUT_LINK):<43} |")
        print(f"| {'VIDEO_RESOLUTION':<20} | {str(VIDEO_RES):<43} |")
        print("="*70 + "\n")

        drone_link = build_drone_link(DRONE_CONNECTION_TYPE, DRONE_PORT, BAUD_RATE)
        tcp_client(drone_link)

    except KeyboardInterrupt:
        print("\n[EXIT] Stopping...")
        running = False
    finally:
        if drone_link:
            drone_link.close()
