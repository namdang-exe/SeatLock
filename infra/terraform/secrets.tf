# ── JWT Keys ──────────────────────────────────────────────────────────────────
# After 'terraform apply', populate these via AWS CLI or Console:
#   aws secretsmanager put-secret-value \
#     --secret-id /seatlock/jwt/private-key \
#     --secret-string "$(cat jwt_private.pem)"

resource "aws_secretsmanager_secret" "jwt_private_key" {
  name        = "/seatlock/jwt/private-key"
  description = "RSA private key (PKCS8 PEM) — user-service signs JWTs"
  tags        = merge(local.common_tags, { Name = "jwt-private-key" })
}

resource "aws_secretsmanager_secret" "jwt_public_key" {
  name        = "/seatlock/jwt/public-key"
  description = "RSA public key (PEM) — venue/booking verify JWTs"
  tags        = merge(local.common_tags, { Name = "jwt-public-key" })
}

# ── Service JWT shared secret ──────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "service_jwt_secret" {
  name        = "/seatlock/service-jwt/secret"
  description = "Shared HS256 secret for booking→venue service JWT"
  tags        = merge(local.common_tags, { Name = "service-jwt-secret" })
}

# ── SMTP credentials ──────────────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "smtp_username" {
  name        = "/seatlock/smtp/username"
  description = "SMTP username (e.g. SES SMTP access key)"
  tags        = merge(local.common_tags, { Name = "smtp-username" })
}

resource "aws_secretsmanager_secret" "smtp_password" {
  name        = "/seatlock/smtp/password"
  description = "SMTP password (e.g. SES SMTP secret)"
  tags        = merge(local.common_tags, { Name = "smtp-password" })
}

# ── RDS master password ────────────────────────────────────────────────────────
# Store the same password you set in terraform.tfvars so ECS tasks can read it.
# After apply: aws secretsmanager put-secret-value \
#   --secret-id /seatlock/db/password --secret-string "yourpassword"

resource "aws_secretsmanager_secret" "db_password" {
  name        = "/seatlock/db/password"
  description = "RDS master password shared by all three databases"
  tags        = merge(local.common_tags, { Name = "db-password" })
}
