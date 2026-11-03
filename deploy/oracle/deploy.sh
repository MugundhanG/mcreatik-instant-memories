#!/usr/bin/env bash
# Deploy the Instant Memories backend on the Oracle VM, next to the website backend, without taking it offline.
# The new version starts in the idle slot (8081 or 8082) while the old one keeps serving; once it is healthy the old
# one stops and Caddy moves traffic over (see slots.sh). :previous is kept for rollback.sh.
#
#   cd ~/mcreatik-instant-memories && git pull && deploy/oracle/deploy.sh
#
# Small VMs (e.g. the 1 GB E2.1.Micro) should not compile Java: use the image GitHub Actions builds instead:
#   IMAGE=ghcr.io/mugundhang/mcreatik-instant-memories-backend:latest GALLERY_MEMORY=900m GALLERY_CPUS=1 deploy/oracle/deploy.sh
#
# One-time step before the first deploy of this version: Caddy must list both slots (Caddyfile.snippet). The script
# checks this and refuses to run otherwise, so a missed step can never cause an outage.
# Still avoid deploying during a live event: ZIP downloads in progress at the switch have to be restarted.
# The container is capped (memory/CPU) so a busy event can never starve the website backend.
set -euo pipefail

LOG=${DEPLOY_LOG:-$HOME/mcreatik-gallery/deploy.log}

# The 1 GB server can stop answering SSH while a new version starts, and a dropped connection would kill the
# script half-way through the switch. So it runs in the background and survives a disconnect; this window just
# follows the log. Reconnect and run `tail -f ~/mcreatik-gallery/deploy.log` to keep watching.
if [[ -z "${DEPLOY_DETACHED:-}" ]]; then
  mkdir -p "$(dirname "$LOG")" && : >"$LOG"
  DEPLOY_DETACHED=1 setsid nohup "$(readlink -f "$0")" "$@" >>"$LOG" 2>&1 </dev/null &
  pid=$!
  echo "==> Deploy running in the background, safe to disconnect (log: $LOG)"
  tail -n +1 -f "$LOG" --pid="$pid" || true
  wait "$pid"
  exit $?
fi

cd "$(dirname "$0")/../.."
# shellcheck source=slots.sh
source deploy/oracle/slots.sh
[[ -r "$ENV_FILE" ]] || { echo "Missing $ENV_FILE (copy deploy/oracle/gallery.env.example and fill it in)"; exit 1; }
require_caddy_slots

if [[ -n "${IMAGE:-}" ]]; then
  echo "==> Pulling $IMAGE"
  docker pull "$IMAGE"
  docker tag "$IMAGE" "$NAME:new"
  commit=$(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$NAME:new" 2>/dev/null | cut -c1-7 || true)
  commit=${commit:-unknown}
else
  commit=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
  echo "==> Building $NAME:new from commit $commit"
  docker build -t "$NAME:new" --label "commit=$commit" backend
fi

if ! switch_to "$NAME:new" "$commit"; then
  exit 1
fi
if docker image inspect "$NAME:current" >/dev/null 2>&1; then
  docker tag "$NAME:current" "$NAME:previous"
fi
docker tag "$NAME:new" "$NAME:current"
echo "==> Done. Roll back with: deploy/oracle/rollback.sh"
