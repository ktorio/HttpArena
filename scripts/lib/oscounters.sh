# scripts/lib/oscounters.sh — host-level OS counters (vmstat, ss -s) captured
# alongside each run's load-generation window.
#
# None of JFR's JVM-internal signals (safepoints, GC, allocation rate, hot
# loops) explain a throughput/CPU decline as connection count rises — the
# remaining suspects (kernel accept backlog, per-fd epoll bookkeeping, socket
# buffer/TIME_WAIT pressure) live below the JVM and don't show up in a JFR
# recording at all. These write plain host-level counters next to the JFR
# profile (same $PROFILES_DIR/$FRAMEWORK mount) so a run's wall-clock window
# can be cross-referenced against both.
#
# Usage:
#   oscounters_start <profile> <conns> <run>   # background vmstat sampler +
#                                                "before" ss -s snapshot
#   oscounters_stop                             # stop sampler, "after" ss -s
#                                                snapshot

OSCOUNTERS_PID=""
OSCOUNTERS_OUT=""

oscounters_start() {
    OSCOUNTERS_PID=""
    OSCOUNTERS_OUT=""

    if ! command -v vmstat >/dev/null 2>&1; then
        warn "vmstat not found — skipping OS-level counter capture"
        return 0
    fi

    local profile="$1" conns="$2" run="$3"
    local dir="$PROFILES_DIR/$FRAMEWORK"
    mkdir -p "$dir"
    OSCOUNTERS_OUT="$dir/oscounters-${profile}-${conns}c-run${run}"

    if command -v ss >/dev/null 2>&1; then
        ss -s > "${OSCOUNTERS_OUT}.ss-before.txt" 2>/dev/null || true
    fi

    # -t timestamps each line so it can be aligned against the JFR
    # recording's wall-clock time. One line/sec: interrupts, context
    # switches, run-queue length, per-category CPU%.
    ( vmstat -t 1 > "${OSCOUNTERS_OUT}.vmstat.log" 2>/dev/null ) &
    OSCOUNTERS_PID=$!
}

oscounters_stop() {
    if [ -n "$OSCOUNTERS_PID" ]; then
        kill "$OSCOUNTERS_PID" 2>/dev/null || true
        wait "$OSCOUNTERS_PID" 2>/dev/null || true
    fi
    OSCOUNTERS_PID=""

    [ -n "$OSCOUNTERS_OUT" ] || return 0
    if command -v ss >/dev/null 2>&1; then
        ss -s > "${OSCOUNTERS_OUT}.ss-after.txt" 2>/dev/null || true
    fi
    OSCOUNTERS_OUT=""
}
