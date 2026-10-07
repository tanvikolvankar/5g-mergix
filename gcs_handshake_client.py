"""
5G MERGIX - Ground Control Station Handshake & Telemetry Client
Connects to Cloud Relay Server (Port 7777), performs authorization,
connects to the spawned relay port, triggers 'START RELAY' on the drone,
and creates a local TCP bridge on port 5760 for Mission Planner / QGroundControl.
"""

import socket
import json
import time
import sys
import threading
import argparse

DEFAULT_SERVER_IP = "64.227.133.143"
APP_PORT = 7777
LOCAL_GCS_PORT = 5760  # Port where Mission Planner connects locally

def run_handshake(server_ip, username, drone_id):
    print("=" * 65)
    print(f"5G MERGIX GCS HANDSHAKE CLIENT")
    print(f"Server Target : {server_ip}:{APP_PORT}")
    print(f"Username      : {username}")
    print(f"Drone ID      : {drone_id}")
    print("=" * 65)

    # 1. Connect to Control Port 7777
    print(f"\n[1/3] Connecting to control port {server_ip}:{APP_PORT} ...")
    try:
        ctrl_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        ctrl_sock.settimeout(10.0)
        ctrl_sock.connect((server_ip, APP_PORT))
    except Exception as e:
        print(f"[ERROR] Could not connect to control port: {e}")
        return

    # 2. Send Auth JSON
    payload = json.dumps({"username": username, "drone_id": drone_id}) + "\n"
    print(f"[2/3] Sending authentication payload: {payload.strip()}")
    try:
        ctrl_sock.sendall(payload.encode('utf-8'))
        response = ctrl_sock.recv(1024).decode('utf-8', errors='ignore').strip()
    except Exception as e:
        print(f"[ERROR] Failed communicating with server: {e}")
        ctrl_sock.close()
        return

    ctrl_sock.close()
    print(f"[SERVER RESPONSE] -> {repr(response)}")

    # Parse response
    lines = response.split("\n")
    status_code = lines[0].strip() if len(lines) > 0 else ""

    if status_code != "1" or len(lines) < 2:
        if "INVALID DRONE_ID" in response:
            print(f"\n[FAIL] Server Error: 'INVALID DRONE_ID'")
            print(f"-> Check that username '{username}' owns '{drone_id}' in userdata.db on server.")
        elif "NA" in response:
            print(f"\n[FAIL] Server Error: 'NA' (Drone Not Active)")
            print(f"-> Make sure the 5G MERGIX app is running on the phone and 'START TELEM' is active!")
        else:
            print(f"\n[FAIL] Unexpected server response: {response}")
        return

    spawned_port = int(lines[1].strip())
    print(f"\n[SUCCESS] Authentication accepted! Spawned relay port: {spawned_port}")

    # 3. Connect to Spawned Relay Port
    print(f"[3/3] Connecting to relay endpoint {server_ip}:{spawned_port} ...")
    time.sleep(0.5)

    try:
        relay_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        relay_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        relay_sock.connect((server_ip, spawned_port))
        print("[CONNECTED] Relay link established with server!")
        print("-> Server has sent 'START RELAY' to the drone!")
        print("-> Bi-directional telemetry is now ACTIVE.")
    except Exception as e:
        print(f"[ERROR] Failed to connect to spawned relay port {spawned_port}: {e}")
        return

    global active_relay_sock
    active_relay_sock = relay_sock

    start_persistent_gcs_server()

    # 4. Receive telemetry from Drone and print/forward
    total_bytes = 0
    start_time = time.time()

    try:
        while True:
            data = relay_sock.recv(4096)
            if not data:
                print("\n[DISCONNECT] Server closed relay connection")
                break

            total_bytes += len(data)
            for gcs in list(gcs_sockets):
                try:
                    gcs.sendall(data)
                except Exception:
                    gcs_sockets.remove(gcs)

            rate = total_bytes / max(1, (time.time() - start_time))
            sys.stdout.write(f"\r[TELEMETRY] Received {total_bytes} bytes ({rate/1024:.2f} KB/s) from Drone | GCS Clients: {len(gcs_sockets)}   ")
            sys.stdout.flush()

    finally:
        active_relay_sock = None
        try:
            relay_sock.close()
        except Exception:
            pass

gcs_sockets = []
active_relay_sock = None
local_server_started = False

def start_persistent_gcs_server():
    global local_server_started
    if local_server_started:
        return
    local_server_started = True

    def _server():
        try:
            local_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            local_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            local_server.bind(("0.0.0.0", LOCAL_GCS_PORT))
            local_server.listen(4)
            print(f"[LOCAL GCS] Persistent listener active on tcp://127.0.0.1:{LOCAL_GCS_PORT}")

            while True:
                client, addr = local_server.accept()
                client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                print(f"\n[LOCAL GCS] Mission Planner / QGC connected from {addr}!")
                gcs_sockets.append(client)

                def handle_gcs(c):
                    while True:
                        try:
                            data = c.recv(2048)
                            if not data:
                                break
                            sock = active_relay_sock
                            if sock:
                                sock.sendall(data)
                        except Exception:
                            break
                    if c in gcs_sockets:
                        gcs_sockets.remove(c)
                    try:
                        c.close()
                    except Exception:
                        pass
                    print(f"\n[LOCAL GCS] Client disconnected")

                threading.Thread(target=handle_gcs, args=(client,), daemon=True).start()
        except Exception as e:
            print(f"[LOCAL SERVER] Note on port {LOCAL_GCS_PORT}: {e}")

    threading.Thread(target=_server, daemon=True).start()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="5G MERGIX GCS Handshake & Telemetry Client")
    parser.add_argument("--server", default=DEFAULT_SERVER_IP, help="Cloud Server IP")
    parser.add_argument("--username", default="ajay", help="Username registered in userdata.db")
    parser.add_argument("--drone_id", default="ajay@1", help="Drone ID registered in userdata.db")
    parser.add_argument("--once", action="store_true", help="Do not auto-reconnect on disconnect")

    args = parser.parse_args()

    while True:
        try:
            run_handshake(args.server, args.username, args.drone_id)
        except KeyboardInterrupt:
            print("\n\n[EXIT] Stopped by user.")
            break
        except Exception as e:
            print(f"\n[ERROR] Connection error: {e}")

        if args.once:
            break
        print("\n[AUTO-RETRY] Connection broke. Re-authenticating & reconnecting in 3 seconds...")
        time.sleep(3)
