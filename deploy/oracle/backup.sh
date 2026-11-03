#!/usr/bin/env bash
# Nightly backup of the Instant Memories database (the Supabase free plan keeps no restorable backups).
#
#   deploy/oracle/backup.sh            # run once by hand to test
#   crontab -e  →  15 3 * * * $HOME/mcreatik-instant-memories/deploy/oracle/backup.sh >> $HOME/mcreatik-gallery/backup.log 2>&1
#   (the server clock is UTC on Oracle images: 15 21 * * * for 02:45 IST)
#
# Writes ~/mcreatik-gallery/backups/db-YYYY-MM-DD.dump (pg_dump custom format, compressed), keeps the last 14 locally,
# and uploads each one to the private originals bucket under backups/ (never the public media bucket, never the
# website's buckets). Settings come from gallery.env; no secret is ever printed or put on a command line.
#
# Restore (into an empty database, e.g. a new Supabase project):
#   docker run --rm -i --env-file <file with PGPASSWORD=…> -v ~/mcreatik-gallery/backups:/b postgres:17-alpine \
#     pg_restore --no-owner --no-privileges -h <host> -p <port> -U <user> -d postgres /b/db-YYYY-MM-DD.dump
# Photos themselves live in R2 (designed for 99.999999999% durability); studios also keep their cards and laptop copies.
set -euo pipefail

ENV_FILE=${ENV_FILE:-$HOME/mcreatik-gallery/gallery.env}
BACKUP_DIR=${BACKUP_DIR:-$HOME/mcreatik-gallery/backups}
KEEP_DAYS=${KEEP_DAYS:-14}
PG_IMAGE=${PG_IMAGE:-postgres:17-alpine}
AWS_IMAGE=${AWS_IMAGE:-amazon/aws-cli:2.22.35}

[[ -r "$ENV_FILE" ]] || { echo "Missing $ENV_FILE"; exit 1; }

# Read KEY=VALUE lines without executing the file (values may contain shell characters).
setting() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true; }

jdbc=$(setting DATABASE_URL)
[[ "$jdbc" == jdbc:postgresql://* ]] || { echo "DATABASE_URL in $ENV_FILE is not a jdbc:postgresql:// URL"; exit 1; }
rest=${jdbc#jdbc:postgresql://}          # host:port/db?params
hostport=${rest%%/*}
dbpath=${rest#*/}
db=${dbpath%%\?*}
host=${hostport%%:*}
port=${hostport##*:}; [[ "$port" == "$hostport" ]] && port=5432
user=$(setting DATABASE_USERNAME)

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
stamp=$(date -u +%Y-%m-%d)
file="db-$stamp.dump"
secrets=$(mktemp)
chmod 600 "$secrets"
trap 'rm -f "$secrets" "$BACKUP_DIR/$file.partial"' EXIT

echo "$(date -u +%FT%TZ) ==> Backing up database $db on $host"
{ echo "PGPASSWORD=$(setting DATABASE_PASSWORD)"; echo "PGSSLMODE=require"; } > "$secrets"
docker run --rm --env-file "$secrets" -v "$BACKUP_DIR:/b" "$PG_IMAGE" \
  pg_dump --format=custom --no-owner --no-privileges --schema=public \
  -h "$host" -p "$port" -U "$user" -d "$db" -f "/b/$file.partial"
mv "$BACKUP_DIR/$file.partial" "$BACKUP_DIR/$file"
echo "==> Saved $BACKUP_DIR/$file ($(du -h "$BACKUP_DIR/$file" | cut -f1))"

bucket=$(setting R2_ORIGINALS_BUCKET)
account=$(setting R2_ACCOUNT_ID)
if [[ -n "$bucket" && -n "$account" && "$(setting STORAGE_TYPE)" == r2 ]]; then
  {
    echo "AWS_ACCESS_KEY_ID=$(setting R2_ACCESS_KEY_ID)"
    echo "AWS_SECRET_ACCESS_KEY=$(setting R2_SECRET_ACCESS_KEY)"
    echo "AWS_DEFAULT_REGION=auto"
    echo "AWS_REQUEST_CHECKSUM_CALCULATION=when_required"
    echo "AWS_RESPONSE_CHECKSUM_VALIDATION=when_required"
  } > "$secrets"
  docker run --rm --env-file "$secrets" -v "$BACKUP_DIR:/b:ro" "$AWS_IMAGE" \
    s3 cp "/b/$file" "s3://$bucket/backups/$file" --endpoint-url "https://$account.r2.cloudflarestorage.com" --only-show-errors
  echo "==> Uploaded to $bucket/backups/$file"
else
  echo "!! R2 settings missing (STORAGE_TYPE=r2, R2_ACCOUNT_ID, R2_ORIGINALS_BUCKET): kept the local copy only"
fi

find "$BACKUP_DIR" -name 'db-*.dump' -mtime +"$KEEP_DAYS" -delete
echo "==> Done; local backups: $(find "$BACKUP_DIR" -name 'db-*.dump' | wc -l)"
