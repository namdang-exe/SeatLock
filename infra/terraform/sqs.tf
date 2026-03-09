# ── Dead-letter queue ─────────────────────────────────────────────────────────

resource "aws_sqs_queue" "dlq" {
  name                      = "${local.prefix}-events-dlq"
  message_retention_seconds = 1209600 # 14 days
  tags                      = merge(local.common_tags, { Name = "${local.prefix}-events-dlq" })
}

# ── Main event queue ──────────────────────────────────────────────────────────

resource "aws_sqs_queue" "events" {
  name                       = "${local.prefix}-events"
  visibility_timeout_seconds = 60
  message_retention_seconds  = 86400 # 24 hours

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = 3
  })

  tags = merge(local.common_tags, { Name = "${local.prefix}-events" })
}

# ── Queue policy: allow booking-service task role to send ─────────────────────

resource "aws_sqs_queue_policy" "events" {
  queue_url = aws_sqs_queue.events.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowECSSend"
        Effect = "Allow"
        Principal = {
          AWS = aws_iam_role.task_role["booking-service"].arn
        }
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.events.arn
      },
      {
        Sid    = "AllowECSReceive"
        Effect = "Allow"
        Principal = {
          AWS = aws_iam_role.task_role["notification-service"].arn
        }
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes"
        ]
        Resource = aws_sqs_queue.events.arn
      }
    ]
  })
}
