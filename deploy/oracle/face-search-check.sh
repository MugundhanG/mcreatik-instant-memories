#!/usr/bin/env bash
# Checks, in one run, every AWS Rekognition permission face search needs, using the keys in gallery.env
# (the same ones the server uses). Prints PASS / FAIL per action and, for a FAIL, which policy refused it.
# Prints no secrets. Creates and deletes a test collection "<prefix>-selfcheck"; touches nothing else.
#
#   bash deploy/oracle/face-search-check.sh            (production: ~/mcreatik-gallery/gallery.env)
#   ENV_FILE=/path/to/gallery.env bash deploy/oracle/face-search-check.sh
set -u
ENV_FILE=${ENV_FILE:-$HOME/mcreatik-gallery/gallery.env}
[[ -r "$ENV_FILE" ]] || { echo "Missing $ENV_FILE"; exit 1; }
setting() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2-; }

REGION=$(setting AWS_REKOGNITION_REGION); REGION=${REGION:-ap-south-1}
PREFIX=$(setting FACE_COLLECTION_PREFIX); PREFIX=${PREFIX:-mck-prod}
COLLECTION="$PREFIX-selfcheck"
[[ -n "$(setting AWS_REKOGNITION_ACCESS_KEY_ID)" && -n "$(setting AWS_REKOGNITION_SECRET_ACCESS_KEY)" ]] \
  || { echo "AWS_REKOGNITION_ACCESS_KEY_ID / _SECRET_ACCESS_KEY are not set in $ENV_FILE"; exit 1; }

creds=$(mktemp); img=$(mktemp --suffix .png)
trap 'rm -f "$creds" "$img"' EXIT
chmod 600 "$creds"
{
  echo "AWS_ACCESS_KEY_ID=$(setting AWS_REKOGNITION_ACCESS_KEY_ID)"
  echo "AWS_SECRET_ACCESS_KEY=$(setting AWS_REKOGNITION_SECRET_ACCESS_KEY)"
  echo "AWS_DEFAULT_REGION=$REGION"
} > "$creds"
# A tiny grey picture with no face: enough to prove each call is allowed (a "no face" answer means allowed).
echo 'iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAS0lEQVR42u3PMQ0AAAwDoEqv9ErYvQQckD4XAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAYHLAB8+AWnmfUycAAAAAElFTkSuQmCC' | base64 -d > "$img"
chmod 644 "$img"

echo "Face search AWS check: region $REGION, test collection $COLLECTION"
docker pull -q amazon/aws-cli >/dev/null || { echo "Could not download the AWS command-line tool (docker pull amazon/aws-cli)"; exit 1; }

FAILS=0
NOFACE=00000000-0000-0000-0000-000000000000
check() { # check "<IAM action>" "<answers that still mean allowed>" <aws rekognition args...>
  local action=$1 allowed=$2; shift 2
  local out code
  out=$(docker run --rm --env-file "$creds" -v "$img:/t.png:ro" amazon/aws-cli rekognition "$@" 2>&1); code=$?
  if grep -q -E 'AccessDenied|not authorized|UnrecognizedClient|InvalidSignature|SignatureDoesNotMatch' <<<"$out"; then
    FAILS=$((FAILS + 1))
    echo "FAIL  rekognition:$action"
    grep -o -E 'with an explicit deny in [^(]*|because no [^(]*|The security token[^(]*|The request signature[^(]*' <<<"$out" | head -1 | sed 's/^/        /'
  elif [[ $code -eq 0 ]] || { [[ -n "$allowed" ]] && grep -q -E "$allowed" <<<"$out"; }; then
    echo "PASS  rekognition:$action"
  else
    FAILS=$((FAILS + 1))
    echo "????  rekognition:$action: $(tail -1 <<<"$out")"
  fi
}

check CreateCollection   'ResourceAlreadyExists'              create-collection --collection-id "$COLLECTION"
check ListCollections    ''                                   list-collections --max-results 1
check IndexFaces         'InvalidParameter|InvalidImageFormat' index-faces --collection-id "$COLLECTION" --image Bytes=fileb:///t.png --max-faces 1
check SearchFacesByImage 'InvalidParameter|InvalidImageFormat' search-faces-by-image --collection-id "$COLLECTION" --image Bytes=fileb:///t.png
check SearchFaces        'InvalidParameter|ResourceNotFound'   search-faces --collection-id "$COLLECTION" --face-id "$NOFACE"
check DeleteFaces        'InvalidParameter|ResourceNotFound'   delete-faces --collection-id "$COLLECTION" --face-ids "$NOFACE"
check DeleteCollection   'ResourceNotFound'                    delete-collection --collection-id "$COLLECTION"

echo
if [[ $FAILS -eq 0 ]]; then
  echo "All 7 face search permissions work. Turn face search off and on for an event to rescan its photos."
else
  echo "$FAILS of 7 failed. Send the FAIL lines (they contain no secrets) to fix them all in one go."
  exit 1
fi
