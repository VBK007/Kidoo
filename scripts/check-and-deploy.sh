#!/usr/bin/env bash
# Periodic check: pull new commits from origin/main and redeploy via docker compose.
# Installed as a crontab entry that self-removes after END_DATE.
set -euo pipefail

REPO_DIR="/home/trojan_vbk/IdeaProjects/Kidoos"
COMPOSE_DIR="/home/trojan_vbk/homeserver"
LOG_FILE="$REPO_DIR/scripts/check-and-deploy.log"
END_DATE="2026-09-17"

log() {
    echo "[$(date -Is)] $*" >> "$LOG_FILE"
}

# Stop scheduling once the one-week window is over.
if [[ "$(date +%Y-%m-%d)" > "$END_DATE" ]]; then
    log "Past end date ($END_DATE), removing self from crontab."
    crontab -l | grep -vF "check-and-deploy.sh" | crontab -
    exit 0
fi

cd "$REPO_DIR"

git fetch origin >> "$LOG_FILE" 2>&1

LOCAL_REV="$(git rev-parse main)"
REMOTE_REV="$(git rev-parse origin/main)"

if [[ "$LOCAL_REV" == "$REMOTE_REV" ]]; then
    exit 0
fi

log "New commits found on origin/main ($LOCAL_REV -> $REMOTE_REV). Pulling and redeploying."

if ! git merge --ff-only origin/main >> "$LOG_FILE" 2>&1; then
    log "ERROR: fast-forward merge failed, local main has diverged. Manual intervention needed."
    exit 1
fi

if (cd "$COMPOSE_DIR" && docker compose up --build -d kidoo) >> "$LOG_FILE" 2>&1; then
    log "Deploy succeeded."
else
    log "ERROR: docker compose up --build -d failed."
    exit 1
fi
