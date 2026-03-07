#!/bin/sh
# Seed shared secrets into Vault for the seatlock-dev-infra profile.
# Runs once on `docker compose up` via the vault-init service.

set -e

echo "Seeding Vault secrets at secret/seatlock ..."

vault kv put secret/seatlock \
  "seatlock.jwt.secret=seatlock-vault-jwt-secret-minimum-32-chars-ok" \
  "seatlock.service-jwt.secret=vault-service-jwt-secret-replace-in-prod-1234" \
  "spring.datasource.password=seatlock"

echo "Vault seed complete."
