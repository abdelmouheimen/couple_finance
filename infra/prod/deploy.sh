#!/bin/sh
# Runs on the server, called by .github/workflows/deploy.yml after the image archive and this directory were copied
# to /opt/couplefinance. Usage: deploy.sh <image-tag>
# Loads the image, starts the stack and waits for the backend readiness probe; on failure the previous image is
# redeployed and the script fails (the workflow then shows the deploy as failed).
set -eu
cd "$(dirname "$0")"
TAG=$1
ARCHIVE=couplefinance-backend-$TAG.tar.gz

./init-env.sh
gunzip -c "$ARCHIVE" | docker load
rm -f "$ARCHIVE"

PREVIOUS=$(sed -n 's/^IMAGE_TAG=//p' .env)
set_tag() { sed -i "s|^IMAGE_TAG=.*|IMAGE_TAG=$1|" .env; }
up() { docker compose --env-file .env up -d --build --remove-orphans; }
port=$(sed -n 's/^BACKEND_PORT=//p' .env); port=${port:-8091}

wait_ready() {
    i=0
    while [ $i -lt 60 ]; do
        if curl -fsS "http://127.0.0.1:$port/actuator/health/readiness" > /dev/null 2>&1; then
            return 0
        fi
        i=$((i + 1)); sleep 5
    done
    return 1
}

set_tag "$TAG"
up
if wait_ready; then
    echo "Deployed couplefinance-backend:$TAG"
    # Keep the previous image for a manual rollback; drop older ones.
    docker images couplefinance-backend --format '{{.Tag}}' \
        | grep -vx -e "$TAG" -e "${PREVIOUS:-none}" | xargs -r -I{} docker rmi "couplefinance-backend:{}" || true
    exit 0
fi

echo "Backend not ready: last logs" >&2
docker compose --env-file .env logs --tail 80 backend >&2 || true
if [ -n "$PREVIOUS" ]; then
    echo "Rolling back to couplefinance-backend:$PREVIOUS" >&2
    set_tag "$PREVIOUS"
    up
    wait_ready || echo "Rollback is not ready either" >&2
fi
exit 1
