#!/usr/bin/env bash
# Build and deploy the Instant Memories backend on the Oracle VM, next to the website backend.
# Same routine as the website: build, test on a spare port, switch, keep :previous for rollback.
#
#   cd ~/mcreatik-instant-memories && git pull && deploy/oracle/deploy.sh
#
# Small VMs (e.g. the 1 GB E2.1.Micro) should not compile Java: use the image GitHub Actions builds instead:
#   IMAGE=ghcr.io/mugundhang/mcreatik-instant-memories-backend:latest GALLERY_MEMORY=900m GALLERY_CPUS=1 deploy/oracle/deploy.sh
#
# The container is capped (memory/CPU) so a busy event can never starve the website backend.
set -euo pipefail

NAME=mcreatik-gallery
ENV_FILE=${ENV_FILE:-$HOME/mcreatik-gallery/gallery.env}
LIVE_PORT=${LIVE_PORT:-8081}
TEST_PORT=${TEST_PORT:-18081}
MEMORY=${GALLERY_MEMORY:-2g}
CPUS=${GALLERY_CPUS:-2}

cd "$(dirname "$0")/../.."
[[ -r "$ENV_FILE" ]] || { echo "Missing $ENV_FILE (copy deploy/oracle/gallery.env.example and fill it in)"; exit 1; }

start() { # name port image restart-policy
  docker run -d --name "$1" --env-file "$ENV_FILE" -e PORT=8080 \
    -p "127.0.0.1:$2:8080" --memory "$MEMORY" --cpus "$CPUS" \
    --log-opt max-size=20m --log-opt max-file=5 \
    --restart "$4" "$3" >/dev/null
}

wait_healthy() { # name port
  for _ in $(seq 1 90); do
    if curl -fsS "http://127.0.0.1:$2/actuator/health" 2>/dev/null | grep -q '"UP"'; then return 0; fi
    if [[ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" != "true" ]]; then break; fi
    sleep 2
  done
  echo "!! $1 did not become healthy. Last log lines:"
  docker logs --tail 60 "$1" || true
  return 1
}

if [[ -n "${IMAGE:-}" ]]; then
  echo "==> Pulling $IMAGE"
  docker pull "$IMAGE"
  docker tag "$IMAGE" "$NAME:new"
  commit=$(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$NAME:new" 2>/dev/null | cut -c1-7)
  commit=${commit:-unknown}
else
  commit=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
  echo "==> Building $NAME:new from commit $commit"
  docker build -t "$NAME:new" --label "commit=$commit" backend
fi

echo "==> Testing the new image on port $TEST_PORT"
docker rm -f "$NAME-test" >/dev/null 2>&1 || true
start "$NAME-test" "$TEST_PORT" "$NAME:new" no
if ! wait_healthy "$NAME-test" "$TEST_PORT"; then
  docker rm -f "$NAME-test" >/dev/null
  echo "Deploy aborted; the running version was not touched."
  exit 1
fi
docker rm -f "$NAME-test" >/dev/null

echo "==> Switching to the new version"
if docker image inspect "$NAME:current" >/dev/null 2>&1; then
  docker tag "$NAME:current" "$NAME:previous"
fi
docker tag "$NAME:new" "$NAME:current"
docker rm -f "$NAME" >/dev/null 2>&1 || true
start "$NAME" "$LIVE_PORT" "$NAME:current" unless-stopped
if wait_healthy "$NAME" "$LIVE_PORT"; then
  echo "==> Live: $NAME ($commit) on 127.0.0.1:$LIVE_PORT"
else
  echo "Rollback with: deploy/oracle/rollback.sh"
  exit 1
fi
