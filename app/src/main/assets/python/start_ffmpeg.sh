#!/bin/bash
# ============================================================
# RTSP relay + watchdog (low-CPU, freeze-proof version)
#
# Restarts ffmpeg when ANY of the following happens:
#   1. Frame counter stops advancing for STALL_LIMIT seconds
#      (encoding is running but producing no new frames)
#   2. Zero progress output at all for FREEZE_LIMIT seconds
#      (true hang: ffmpeg blocked on a read/write syscall,
#       e.g. stuck connecting to output server)
#   3. ffmpeg process exits/crashes on its own
#   4. ffmpeg reports a clean end of stream
#
# Requires: bash >= 4 (for coproc), ffmpeg, python3
# ============================================================
set -u

STATIC_JSON="/home/$USER/Desktop/static_mergix_data.json"
LOGFILE="/tmp/ffmpeg.log"

STALL_LIMIT=5     # seconds of unchanged frame count => stalled
FREEZE_LIMIT=5    # seconds of zero progress output   => frozen/hung
RESTART_DELAY=2   # seconds to wait before relaunching ffmpeg

FFMPEG_PID=""

# ---- graceful shutdown if the watchdog itself is stopped/killed ----
cleanup() {
    if [ -n "$FFMPEG_PID" ] && kill -0 "$FFMPEG_PID" 2>/dev/null; then
        kill -TERM "$FFMPEG_PID" 2>/dev/null
        sleep 1
        kill -0 "$FFMPEG_PID" 2>/dev/null && kill -KILL "$FFMPEG_PID" 2>/dev/null
    fi
    exit 0
}
trap cleanup INT TERM

# ---- read a key from the JSON config, with fallback ----
get_json_val() {
    local key=$1 default=$2
    if [ -f "$STATIC_JSON" ]; then
        local val
        val=$(python3 -c "
import json
try:
    d = json.load(open('$STATIC_JSON'))
    v = d.get('$key')
    print(v if v is not None else '')
except Exception:
    print('')
" 2>/dev/null)
        if [ -n "$val" ] && [ "$val" != "None" ]; then
            echo "$val"
            return
        fi
    fi
    echo "$default"
}

# ---- terminate ffmpeg cleanly, escalate to SIGKILL if needed ----
kill_ffmpeg() {
    local pid=$1
    kill -TERM "$pid" 2>/dev/null
    for _ in 1 2 3 4 5; do
        sleep 1
        kill -0 "$pid" 2>/dev/null || return 0
    done
    echo "$(date) FFmpeg still alive after SIGTERM, sending SIGKILL" | tee -a "$LOGFILE"
    kill -KILL "$pid" 2>/dev/null
}

while true; do
    VIDEO_INPUT=$(get_json_val "video_input_link" "rtsp://192.168.144.25:8554/main.264")
    VIDEO_OUTPUT=$(get_json_val "video_output_link" "rtsp://2.2.2.2:8554/mystream1")
    VIDEO_RES=$(get_json_val "video_res" "630:360")

    # Cap the log instead of wiping it every restart, so recurring
    # failure patterns across restarts stay visible while size stays bounded.
    if [ -f "$LOGFILE" ]; then
        tail -n 1000 "$LOGFILE" > "${LOGFILE}.tmp" 2>/dev/null && mv "${LOGFILE}.tmp" "$LOGFILE"
    fi
    echo "$(date) Starting FFmpeg..." | tee -a "$LOGFILE"
    echo "INPUT     : $VIDEO_INPUT"   | tee -a "$LOGFILE"
    echo "OUTPUT    : $VIDEO_OUTPUT"  | tee -a "$LOGFILE"
    echo "RESOLUTION: $VIDEO_RES"    | tee -a "$LOGFILE"

    # coproc gives us BOTH a real PID and a pipe fd, cleanly,
    # with no subshell-PID ambiguity like a plain `cmd | { ... }` pipe.
    # `exec ffmpeg` replaces the coproc's shell with ffmpeg itself,
    # so FFPROC_PID *is* ffmpeg's actual PID.
    coproc FFPROC {
        exec ffmpeg \
            -nostdin \
            -loglevel error \
            -rtsp_transport tcp \
            -timeout 5000000 \
            -i "$VIDEO_INPUT" \
            -vf scale="$VIDEO_RES" \
            -c:v libx264 \
            -preset superfast \
            -tune zerolatency \
            -an \
            -f rtsp \
            -rtsp_transport tcp \
            -progress pipe:1 \
            "$VIDEO_OUTPUT" 2>>"$LOGFILE"
    }
    FFMPEG_PID=$FFPROC_PID
    # We never write to ffmpeg's stdin (it's launched with -nostdin); close
    # our copy of the coproc's write end right away. Bash doesn't auto-close
    # coproc fds when a new coproc overwrites the array on the next restart,
    # so leaving this open would leak an fd every single restart cycle.
    eval "exec ${FFPROC[1]}>&-" 2>/dev/null
    echo "$(date) FFmpeg started, PID $FFMPEG_PID" | tee -a "$LOGFILE"

    LAST_FRAME=-1
    STALL_COUNTER=0
    FREEZE_COUNTER=0
    REASON=""

    while true; do
        # -t 1 wakes this up every second REGARDLESS of whether ffmpeg
        # wrote anything -- this is what catches a true hang.
        IFS='=' read -t 1 -r key value <&"${FFPROC[0]}"
        rc=$?

        if [ $rc -eq 0 ]; then
            # got a line -> ffmpeg is definitely alive and producing output
            FREEZE_COUNTER=0
            value=${value%$'\r'}
            case "$key" in
                frame)
                    value=$(echo "$value" | tr -dc '0-9')
                    if [ -n "$value" ]; then
                        if [ "$value" = "$LAST_FRAME" ]; then
                            STALL_COUNTER=$((STALL_COUNTER + 1))
                        else
                            LAST_FRAME=$value
                            STALL_COUNTER=0
                        fi
                        echo "Frame=$LAST_FRAME Stalled=${STALL_COUNTER}s"
                    fi
                    ;;
                progress)
                    if [ "$value" = "end" ]; then
                        REASON="ffmpeg reported end of stream"
                        break
                    fi
                    ;;
            esac
        elif [ $rc -gt 128 ]; then
            # bash convention: read's exit status is >128 on timeout.
            # No line arrived in the last second at all.
            FREEZE_COUNTER=$((FREEZE_COUNTER + 1))
            echo "No output for ${FREEZE_COUNTER}s"
        else
            # EOF: the pipe closed, meaning ffmpeg's process has ended
            REASON="ffmpeg pipe closed (process exited)"
            break
        fi

        if [ "$STALL_COUNTER" -ge "$STALL_LIMIT" ]; then
            REASON="frame count stalled for ${STALL_LIMIT}s"
            break
        fi
        if [ "$FREEZE_COUNTER" -ge "$FREEZE_LIMIT" ]; then
            REASON="no output at all for ${FREEZE_LIMIT}s (hard freeze)"
            break
        fi
        # belt-and-suspenders: catch a dead process even if the pipe
        # somehow didn't signal EOF yet
        if ! kill -0 "$FFMPEG_PID" 2>/dev/null; then
            REASON="ffmpeg process no longer running"
            break
        fi
    done

    echo "$(date) Restart triggered: $REASON" | tee -a "$LOGFILE"

    # Close our copy of the coproc's read fd before the next iteration
    # opens a fresh one -- same fd-leak reasoning as the write end above.
    eval "exec ${FFPROC[0]}<&-" 2>/dev/null

    if kill -0 "$FFMPEG_PID" 2>/dev/null; then
        kill_ffmpeg "$FFMPEG_PID"
    fi
    wait "$FFMPEG_PID" 2>/dev/null

    echo "$(date) Restarting in ${RESTART_DELAY}s..." | tee -a "$LOGFILE"
    sleep "$RESTART_DELAY"
done