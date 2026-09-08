#!/bin/sh
set -eu
# Single dev startup: import JSON from /opt/keycloak/data/import/*.json (see compose volume).
# A separate `kc.sh import` pass can fail or race DB init; missing realm → browser 404 on
# /auth/realms/prime/protocol/openid-connect/auth
exec /opt/keycloak/bin/kc.sh start-dev --import-realm --transaction-xa-enabled=false
