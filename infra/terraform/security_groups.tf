# ── ALB ───────────────────────────────────────────────────────────────────────

resource "aws_security_group" "alb" {
  name        = "${local.prefix}-alb"
  description = "Allow HTTP/HTTPS from the internet to the ALB"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, { Name = "${local.prefix}-sg-alb" })
}

# ── ECS tasks ─────────────────────────────────────────────────────────────────

resource "aws_security_group" "ecs" {
  name        = "${local.prefix}-ecs"
  description = "Allow traffic from ALB to ECS tasks, and all outbound"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 8081
    to_port         = 8084
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # Allow ECS tasks to call each other (Cloud Map internal routing)
  ingress {
    from_port = 8081
    to_port   = 8084
    protocol  = "tcp"
    self      = true
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, { Name = "${local.prefix}-sg-ecs" })
}

# ── RDS ───────────────────────────────────────────────────────────────────────

resource "aws_security_group" "rds" {
  name        = "${local.prefix}-rds"
  description = "Allow Postgres from ECS tasks only"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, { Name = "${local.prefix}-sg-rds" })
}

# ── ElastiCache ───────────────────────────────────────────────────────────────

resource "aws_security_group" "redis" {
  name        = "${local.prefix}-redis"
  description = "Allow Redis from ECS tasks only"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, { Name = "${local.prefix}-sg-redis" })
}
