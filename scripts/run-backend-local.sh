#!/usr/bin/env bash
# Runs the backend against a local PostgreSQL with filesystem storage (no R2 needed).
# Usage: scripts/run-backend-local.sh   (build first: cd backend && mvn -q package -DskipTests)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export DATABASE_URL="${DATABASE_URL:-jdbc:postgresql://localhost:5432/mcreatik}"
export DATABASE_USERNAME="${DATABASE_USERNAME:-mcreatik}"
export DATABASE_PASSWORD="${DATABASE_PASSWORD:-mcreatik}"
export STORAGE_TYPE=local
export LOCAL_STORAGE_ROOT="${LOCAL_STORAGE_ROOT:-$ROOT/data/storage}"
export ADMIN_EMAIL="${ADMIN_EMAIL:-admin@mcreatik.local}"
export ADMIN_PASSWORD="${ADMIN_PASSWORD:-change-me-please}"
export JWT_SECRET="${JWT_SECRET:-local-dev-secret-local-dev-secret-0123456789}"
export UPLOADER_ONLINE_THRESHOLD="${UPLOADER_ONLINE_THRESHOLD:-45s}"
exec java -jar "$ROOT/backend/target/live-gallery-backend.jar"
