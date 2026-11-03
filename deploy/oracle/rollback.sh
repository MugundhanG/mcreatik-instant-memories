#!/usr/bin/env bash
# Switch the Instant Memories backend back to the image that was live before the last deploy, without downtime:
# :previous starts in the idle slot and takes over once healthy (see slots.sh).
#
#   GALLERY_MEMORY=900m GALLERY_CPUS=1 deploy/oracle/rollback.sh
#
# Use the same GALLERY_MEMORY / GALLERY_CPUS as your deploy (the 1 GB VM needs 900m and 1 CPU).
set -euo pipefail
cd "$(dirname "$0")/../.."
# shellcheck source=slots.sh
source deploy/oracle/slots.sh

# Check everything before touching the running version.
docker image inspect "$NAME:previous" >/dev/null 2>&1 || { echo "No $NAME:previous image to roll back to."; exit 1; }
[[ -r "$ENV_FILE" ]] || { echo "Missing $ENV_FILE"; exit 1; }
require_caddy_slots

echo "==> Rolling back to $NAME:previous"
switch_to "$NAME:previous" "previous version"
docker tag "$NAME:current" "$NAME:failed" 2>/dev/null || true
docker tag "$NAME:previous" "$NAME:current"
echo "==> Rolled back (the version that failed is tagged $NAME:failed)."
