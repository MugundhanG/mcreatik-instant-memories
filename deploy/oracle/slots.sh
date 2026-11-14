# shellcheck shell=bash
# Shared by deploy.sh and rollback.sh: the two "slots" the backend runs in, so an update never takes it offline.
#
#   blue  = container mcreatik-gallery-blue  on 127.0.0.1:8081
#   green = container mcreatik-gallery-green on 127.0.0.1:8082
#
# Caddy sends traffic to whichever slot answers its health check (see Caddyfile.snippet). An update starts the new
# version in the idle slot, waits until it is healthy, then stops the old slot; Caddy retries any request that hits
# the stopping slot on the other one, so guests never see an error page.
# A container from before slots ("mcreatik-gallery" on 8081) counts as blue.
#
# Everything below has a production default, so prod commands are unchanged. A second environment on its own VM
# (beta) normally needs no overrides either; set these only to run two environments on one machine:
#   SLOT_PREFIX  container/image name prefix      (default mcreatik-gallery)
#   BLUE_PORT / GREEN_PORT                         (default 8081 / 8082; keep them in step with the Caddyfile)
#   ENV_DIR      folder holding gallery.env, deploy.log, backups/   (default ~/mcreatik-gallery)
#   ENV_FILE     the env file itself                (default $ENV_DIR/gallery.env)

NAME=${SLOT_PREFIX:-mcreatik-gallery}
BLUE_PORT=${BLUE_PORT:-8081}
GREEN_PORT=${GREEN_PORT:-8082}
ENV_DIR=${ENV_DIR:-$HOME/mcreatik-gallery}
ENV_FILE=${ENV_FILE:-$ENV_DIR/gallery.env}
CADDYFILE=${CADDYFILE:-/etc/caddy/Caddyfile}
MEMORY=${GALLERY_MEMORY:-2g}
CPUS=${GALLERY_CPUS:-2}
# Fast servers answer in ~20 s; the 1 GB E2.1.Micro can need over 10 minutes while two copies start side by side.
HEALTH_WAIT_SECONDS=${HEALTH_WAIT_SECONDS:-1200}
# Time for Caddy's health check (every 5 s) to see the new slot before the old one stops.
SWITCH_GRACE_SECONDS=${SWITCH_GRACE_SECONDS:-12}

slot_port() { [[ "$1" == blue ]] && echo "$BLUE_PORT" || echo "$GREEN_PORT"; }
other_slot() { [[ "$1" == blue ]] && echo green || echo blue; }
is_running() { [[ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" == "true" ]]; }

# Prints the container that is live now ("" if none).
live_container() {
  if is_running "$NAME-blue"; then echo "$NAME-blue"
  elif is_running "$NAME-green"; then echo "$NAME-green"
  elif is_running "$NAME"; then echo "$NAME"
  fi
}

# The slot a container occupies (the legacy container lives on 8081, i.e. blue).
slot_of() { [[ "$1" == "$NAME-green" ]] && echo green || echo blue; }

# Refuses to continue unless Caddy balances across both slots: otherwise stopping the old slot would be an outage.
require_caddy_slots() {
  if [[ -n "${SKIP_CADDY_CHECK:-}" ]]; then return 0; fi
  if [[ -r "$CADDYFILE" ]] && grep -q "127.0.0.1:$BLUE_PORT" "$CADDYFILE" && grep -q "127.0.0.1:$GREEN_PORT" "$CADDYFILE"; then
    return 0
  fi
  echo "!! Caddy is not set up for updates without downtime yet ($CADDYFILE must list 127.0.0.1:$BLUE_PORT and 127.0.0.1:$GREEN_PORT)."
  echo "   One-time step: replace the API block with deploy/oracle/Caddyfile.snippet (beta: Caddyfile.beta.snippet), then run:"
  echo "     sudo caddy validate --config $CADDYFILE && sudo systemctl reload caddy"
  echo "   Nothing was changed; the running version keeps serving."
  exit 1
}

start() { # name port image
  docker run -d --name "$1" --env-file "$ENV_FILE" -e PORT=8080 \
    -p "127.0.0.1:$2:8080" --memory "$MEMORY" --cpus "$CPUS" \
    --log-opt max-size=20m --log-opt max-file=5 \
    --restart unless-stopped "$3" >/dev/null
}

wait_healthy() { # name port
  for _ in $(seq 1 $((HEALTH_WAIT_SECONDS / 2))); do
    if curl -fsS "http://127.0.0.1:$2/actuator/health" 2>/dev/null | grep -q '"UP"'; then return 0; fi
    if ! is_running "$1"; then break; fi
    sleep 2
  done
  echo "!! $1 did not become healthy. Last log lines:"
  docker logs --tail 60 "$1" || true
  return 1
}

# Starts IMAGE in the idle slot, and once it is healthy retires the live one. Leaves the live one untouched on failure.
switch_to() { # image label
  local image=$1 label=$2 live new port
  live=$(live_container)
  if [[ -n "$live" ]]; then new=$(other_slot "$(slot_of "$live")"); else new=blue; fi
  port=$(slot_port "$new")

  echo "==> Starting $label in the $new slot (127.0.0.1:$port)${live:+; $live keeps serving meanwhile}"
  docker rm -f "$NAME-$new" >/dev/null 2>&1 || true
  start "$NAME-$new" "$port" "$image"
  if ! wait_healthy "$NAME-$new" "$port"; then
    docker rm -f "$NAME-$new" >/dev/null 2>&1 || true
    echo "Aborted; ${live:-nothing} is still live and was not touched."
    return 1
  fi

  if [[ -n "$live" ]]; then
    echo "==> $NAME-$new is healthy; letting Caddy notice (${SWITCH_GRACE_SECONDS}s), then stopping $live"
    sleep "$SWITCH_GRACE_SECONDS"
    docker stop -t 20 "$live" >/dev/null || true
    docker rm "$live" >/dev/null 2>&1 || true
  fi
  echo "==> Live: $NAME-$new ($label) on 127.0.0.1:$port"
}
