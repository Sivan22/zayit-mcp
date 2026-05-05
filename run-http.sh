#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${1:-3001}"
exec java -jar "$DIR/build/libs/zayit-mcp.jar" --port "$PORT"
