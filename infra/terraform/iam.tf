# ── ECS Task Execution Role (shared by all services) ─────────────────────────
# Used by the ECS agent to pull images from ECR and inject secrets.

resource "aws_iam_role" "task_execution" {
  name = "${local.prefix}-task-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "task_execution_managed" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Allow reading secrets from Secrets Manager
resource "aws_iam_role_policy" "task_execution_secrets" {
  name = "${local.prefix}-read-secrets"
  role = aws_iam_role.task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:/seatlock/*"
    }]
  })
}

# ── Per-service Task Roles (least privilege for runtime AWS calls) ─────────────

locals {
  task_role_services = toset(["user-service", "venue-service", "booking-service", "notification-service"])
}

resource "aws_iam_role" "task_role" {
  for_each = local.task_role_services
  name     = "${local.prefix}-${each.key}-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = merge(local.common_tags, { Service = each.key })
}

# booking-service: send to SQS
resource "aws_iam_role_policy" "booking_sqs" {
  name = "${local.prefix}-booking-sqs-send"
  role = aws_iam_role.task_role["booking-service"].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sqs:SendMessage", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
      Resource = aws_sqs_queue.events.arn
    }]
  })
}

# notification-service: receive/delete from SQS
resource "aws_iam_role_policy" "notification_sqs" {
  name = "${local.prefix}-notification-sqs-consume"
  role = aws_iam_role.task_role["notification-service"].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueUrl",
        "sqs:GetQueueAttributes"
      ]
      Resource = aws_sqs_queue.events.arn
    }]
  })
}
