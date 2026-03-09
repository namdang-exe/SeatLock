resource "aws_ecs_cluster" "main" {
  name = "${local.prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = merge(local.common_tags, { Name = "${local.prefix}-cluster" })
}

resource "aws_cloudwatch_log_group" "services" {
  for_each          = toset(local.services)
  name              = "/ecs/${local.prefix}/${each.key}"
  retention_in_days = 14
  tags              = merge(local.common_tags, { Service = each.key })
}

# ── user-service task definition ──────────────────────────────────────────────

resource "aws_ecs_task_definition" "user_service" {
  family                   = "${local.prefix}-user-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task_role["user-service"].arn

  container_definitions = jsonencode([{
    name      = "user-service"
    image     = "${local.account_id}.dkr.ecr.${local.region}.amazonaws.com/${local.prefix}/user-service:${var.app_version}"
    essential = true

    portMappings = [{
      containerPort = 8081
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
      { name = "RDS_ENDPOINT", value = aws_db_instance.services["user"].address },
      { name = "RDS_USERNAME", value = var.db_username }
    ]

    secrets = [
      { name = "RDS_PASSWORD",             valueFrom = aws_secretsmanager_secret.db_password.arn },
      { name = "SEATLOCK_JWT_PRIVATE_KEY", valueFrom = aws_secretsmanager_secret.jwt_private_key.arn },
      { name = "SEATLOCK_JWT_PUBLIC_KEY",  valueFrom = aws_secretsmanager_secret.jwt_public_key.arn }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.services["user-service"].name
        "awslogs-region"        = local.region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:8081/actuator/health || exit 1"]
      interval    = 30
      timeout     = 10
      retries     = 3
      startPeriod = 60
    }
  }])

  tags = merge(local.common_tags, { Service = "user-service" })
}

# ── venue-service task definition ─────────────────────────────────────────────

resource "aws_ecs_task_definition" "venue_service" {
  family                   = "${local.prefix}-venue-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task_role["venue-service"].arn

  container_definitions = jsonencode([{
    name      = "venue-service"
    image     = "${local.account_id}.dkr.ecr.${local.region}.amazonaws.com/${local.prefix}/venue-service:${var.app_version}"
    essential = true

    portMappings = [{
      containerPort = 8082
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
      { name = "RDS_ENDPOINT",           value = aws_db_instance.services["venue"].address },
      { name = "RDS_USERNAME",           value = var.db_username },
      { name = "REDIS_HOST",             value = aws_elasticache_cluster.redis.cache_nodes[0].address },
      { name = "AWS_REGION",             value = local.region }
    ]

    secrets = [
      { name = "RDS_PASSWORD",                valueFrom = aws_secretsmanager_secret.db_password.arn },
      { name = "SEATLOCK_JWT_PUBLIC_KEY",     valueFrom = aws_secretsmanager_secret.jwt_public_key.arn },
      { name = "SEATLOCK_SERVICE_JWT_SECRET", valueFrom = aws_secretsmanager_secret.service_jwt_secret.arn }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.services["venue-service"].name
        "awslogs-region"        = local.region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:8082/actuator/health || exit 1"]
      interval    = 30
      timeout     = 10
      retries     = 3
      startPeriod = 60
    }
  }])

  tags = merge(local.common_tags, { Service = "venue-service" })
}

# ── booking-service task definition ───────────────────────────────────────────

resource "aws_ecs_task_definition" "booking_service" {
  family                   = "${local.prefix}-booking-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task_role["booking-service"].arn

  container_definitions = jsonencode([{
    name      = "booking-service"
    image     = "${local.account_id}.dkr.ecr.${local.region}.amazonaws.com/${local.prefix}/booking-service:${var.app_version}"
    essential = true

    portMappings = [{
      containerPort = 8083
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE",  value = "prod" },
      { name = "RDS_ENDPOINT",            value = aws_db_instance.services["booking"].address },
      { name = "VENUE_RDS_ENDPOINT",      value = aws_db_instance.services["venue"].address },
      { name = "RDS_USERNAME",            value = var.db_username },
      { name = "REDIS_HOST",              value = aws_elasticache_cluster.redis.cache_nodes[0].address },
      { name = "SQS_QUEUE_URL",           value = aws_sqs_queue.events.url },
      { name = "AWS_REGION",              value = local.region }
    ]

    secrets = [
      { name = "RDS_PASSWORD",                valueFrom = aws_secretsmanager_secret.db_password.arn },
      { name = "SEATLOCK_JWT_PUBLIC_KEY",     valueFrom = aws_secretsmanager_secret.jwt_public_key.arn },
      { name = "SEATLOCK_SERVICE_JWT_SECRET", valueFrom = aws_secretsmanager_secret.service_jwt_secret.arn }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.services["booking-service"].name
        "awslogs-region"        = local.region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:8083/actuator/health || exit 1"]
      interval    = 30
      timeout     = 10
      retries     = 3
      startPeriod = 60
    }
  }])

  tags = merge(local.common_tags, { Service = "booking-service" })
}

# ── notification-service task definition ──────────────────────────────────────

resource "aws_ecs_task_definition" "notification_service" {
  family                   = "${local.prefix}-notification-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task_role["notification-service"].arn

  container_definitions = jsonencode([{
    name      = "notification-service"
    image     = "${local.account_id}.dkr.ecr.${local.region}.amazonaws.com/${local.prefix}/notification-service:${var.app_version}"
    essential = true

    portMappings = [{
      containerPort = 8084
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE",          value = "prod" },
      { name = "SQS_QUEUE_NAME",                  value = aws_sqs_queue.events.name },
      { name = "SMTP_FROM_ADDRESS",               value = var.smtp_from_address },
      { name = "SMTP_DEFAULT_RECIPIENT",          value = var.smtp_default_recipient },
      { name = "AWS_REGION",                      value = local.region }
    ]

    secrets = [
      { name = "SMTP_USERNAME", valueFrom = aws_secretsmanager_secret.smtp_username.arn },
      { name = "SMTP_PASSWORD", valueFrom = aws_secretsmanager_secret.smtp_password.arn }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.services["notification-service"].name
        "awslogs-region"        = local.region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:8084/actuator/health || exit 1"]
      interval    = 30
      timeout     = 10
      retries     = 3
      startPeriod = 60
    }
  }])

  tags = merge(local.common_tags, { Service = "notification-service" })
}

# ── ECS Services ──────────────────────────────────────────────────────────────

locals {
  ecs_services = {
    "user-service" = {
      task_definition = aws_ecs_task_definition.user_service.arn
      container_port  = 8081
    }
    "venue-service" = {
      task_definition = aws_ecs_task_definition.venue_service.arn
      container_port  = 8082
    }
    "booking-service" = {
      task_definition = aws_ecs_task_definition.booking_service.arn
      container_port  = 8083
    }
    "notification-service" = {
      task_definition = aws_ecs_task_definition.notification_service.arn
      container_port  = 8084
    }
  }
}

resource "aws_ecs_service" "services" {
  for_each = local.ecs_services

  name            = "${local.prefix}-${each.key}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = each.value.task_definition
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  # Allow ECS to replace tasks without waiting for deregistration
  force_new_deployment = true

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = false
  }

  # Only register non-notification services with the ALB
  dynamic "load_balancer" {
    for_each = each.key != "notification-service" ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.services[each.key].arn
      container_name   = each.key
      container_port   = each.value.container_port
    }
  }

  service_registries {
    registry_arn = aws_service_discovery_service.services[each.key].arn
  }

  depends_on = [
    aws_lb_listener.http,
    aws_iam_role_policy_attachment.task_execution_managed
  ]

  tags = merge(local.common_tags, { Service = each.key })

  lifecycle {
    # Prevent Terraform from overwriting the task definition on manual deploys
    ignore_changes = [task_definition]
  }
}
