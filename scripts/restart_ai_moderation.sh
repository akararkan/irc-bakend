#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Restart every AI-moderation container:
#
#   model-inference  :8000  — text toxicity scorer   (docs/model-inference)
#   image-inference  :8002  — NSFW image scorer      (docs/model-image-inference)
#   model-training   :8001  — fine-tune service      (profile "on-demand";
#                             only restarted when it is already running)
#
# "Restart" here means `up -d --build --force-recreate`: containers come back
# even if they were never created, and a changed Dockerfile/app is picked up.
# Docker layer caching makes a no-change run cheap; the FIRST image-inference
# run builds torch (~minutes) and then downloads the ~350MB checkpoint into the
# image-model-cache volume, so allow time before its health check goes green.
#
# Usage:  scripts/restart_ai_moderation.sh [--no-wait]
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")/.."   # repo root, where docker-compose.yml lives

WAIT=true
[[ "${1:-}" == "--no-wait" ]] && WAIT=false

CORE_SERVICES=(model-inference image-inference)

echo "── Restarting AI moderation containers ─────────────────────────────"
docker compose up -d --build --force-recreate "${CORE_SERVICES[@]}"

# model-training is behind the on-demand profile and saturates the CPU while
# up — never start it implicitly; only bounce it if someone already has it up.
if [[ -n "$(docker compose --profile on-demand ps -q model-training 2>/dev/null)" ]]; then
    echo "model-training is running — restarting it too"
    docker compose --profile on-demand up -d --build --force-recreate model-training
else
    echo "model-training not running (on-demand profile) — leaving it down"
fi

$WAIT || { echo "Started (skipping health wait)."; exit 0; }

# ── health waits ────────────────────────────────────────────────────────────
wait_healthy() {
    local name="$1" url="$2" timeout_s="$3" waited=0
    printf "waiting for %-16s %s " "$name" "$url"
    until curl -sf --max-time 3 "$url" >/dev/null 2>&1; do
        if (( waited >= timeout_s )); then
            echo " ✗ not healthy after ${timeout_s}s"
            echo "   → docker compose logs --tail 50 $name"
            return 1
        fi
        sleep 5; waited=$((waited + 5)); printf "."
    done
    echo " ✓ up ($(curl -sf --max-time 3 "$url"))"
}

# A recreate resets the resident TEXT model to its boot default (the base
# checkpoint) — the promoted artifact is normally hot-loaded via /v1/reload at
# promote time and does NOT survive recreation. Re-assert it here, or the
# scorer silently serves base weights while reporting healthy.
# Override with MODERATION_ACTIVE_VERSION=vN; default = highest vN in the volume.
reload_text_artifact() {
    local version="${MODERATION_ACTIVE_VERSION:-}"
    if [[ -z "$version" ]]; then
        local latest_n
        latest_n=$(docker compose exec -T model-inference sh -c 'ls /app/model 2>/dev/null' \
                   | grep -E '^v[0-9]+$' | sed 's/^v//' | sort -n | tail -1 || true)
        [[ -n "$latest_n" ]] && version="v${latest_n}"
    fi
    if [[ -z "$version" ]]; then
        echo "no versioned text artifact in the volume — serving the boot default"
        return 0
    fi
    printf "re-asserting text model artifact %s → " "$version"
    local reply
    if [[ -n "${MODERATION_INFERENCE_API_KEY:-}" ]]; then
        reply=$(curl -sf -X POST http://localhost:8000/v1/reload \
                -H "Content-Type: application/json" \
                -H "X-API-Key: ${MODERATION_INFERENCE_API_KEY}" \
                -d "{\"version\":\"${version}\"}") || { echo "reload FAILED — check manually"; return 1; }
    else
        reply=$(curl -sf -X POST http://localhost:8000/v1/reload \
                -H "Content-Type: application/json" \
                -d "{\"version\":\"${version}\"}") || { echo "reload FAILED — check manually"; return 1; }
    fi
    echo "$reply"
}

FAILED=0
wait_healthy model-inference "http://localhost:8000/healthz" 180 && reload_text_artifact || FAILED=1
# Generous: first boot = image build already done above, but the checkpoint
# download (~350MB) happens inside the container on startup.
wait_healthy image-inference "http://localhost:8002/healthz" 600 || FAILED=1

echo "─────────────────────────────────────────────────────────────────────"
docker compose ps model-inference image-inference
exit $FAILED
