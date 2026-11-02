#!/usr/bin/env bash
# Switch the Instant Memories backend back to the image that was live before the last deploy.
#
#   GALLERY_MEMORY=900m GALLERY_CPUS=1 deploy/oracle/rollback.sh
#
# Use the same GALLERY_MEMORY / GALLERY_CPUS as your deploy (the 1 GB VM needs 900m and 1 CPU).
set -euo pipefail

NAME=mcreatik-gallery
ENV_FILE=${ENV_FILE:-$HOME/mcreatik-gallery/gallery.env}
LIVE_PORT=${LIVE_PORT:-8081}
MEMORY=${GALLERY_MEMORY:-2g}
CPUS=${GALLERY_CPUS:-2}
HEALTH_WAIT_SECONDS=${HEALTH_WAIT_SECONDS:-1200}

# Check everything before touching the running version.
docker image inspect "$NAME:previous" >/dev/null 2>&1 || { echo "No $NAME:previous image to roll back to."; exit 1; }
[[ -r "$ENV_FILE" ]] || { echo "Missing $ENV_FILE"; exit 1; }

echo "==> Rolling back to $NAME:previous"
docker tag "$NAME:current" "$NAME:failed" 2>/dev/null || true
docker tag "$NAME:previous" "$NAME:current"
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --env-file "$ENV_FILE" -e PORT=8080 \
  -p "127.0.0.1:$LIVE_PORT:8080" --memory "$MEMORY" --cpus "$CPUS" \
  --log-opt max-size=20m --log-opt max-file=5 \
  --restart unless-stopped "$NAME:current" >/dev/null

echo "==> Waiting for it to answer on 127.0.0.1:$LIVE_PORT"
for _ in $(seq 1 $((HEALTH_WAIT_SECONDS / 2))); do
  if curl -fsS "http://127.0.0.1:$LIVE_PORT/actuator/health" 2>/dev/null | grep -q '"UP"'; then
    echo "==> Rolled back: $NAME is live again (the version that failed is tagged $NAME:failed)."
    exit 0
  fi
  if [[ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" != "true" ]]; then break; fi
  sleep 2
done
echo "!! $NAME did not become healthy. Last log lines:"
docker logs --tail 60 "$NAME" || true
exit 1
