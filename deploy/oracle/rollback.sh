#!/usr/bin/env bash
# Switch the Instant Memories backend back to the image that was live before the last deploy.
set -euo pipefail
NAME=mcreatik-gallery
ENV_FILE=${ENV_FILE:-$HOME/mcreatik-gallery/gallery.env}
LIVE_PORT=${LIVE_PORT:-8081}
docker image inspect "$NAME:previous" >/dev/null 2>&1 || { echo "No $NAME:previous image to roll back to."; exit 1; }
docker tag "$NAME:current" "$NAME:failed" 2>/dev/null || true
docker tag "$NAME:previous" "$NAME:current"
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --env-file "$ENV_FILE" -e PORT=8080 -p "127.0.0.1:$LIVE_PORT:8080" \
  --memory "${GALLERY_MEMORY:-2g}" --cpus "${GALLERY_CPUS:-2}"  # match what deploy.sh used \
  --log-opt max-size=20m --log-opt max-file=5 --restart unless-stopped "$NAME:current" >/dev/null
echo "Rolled back. Check: curl -s http://127.0.0.1:$LIVE_PORT/actuator/health"
