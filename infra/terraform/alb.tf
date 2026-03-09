resource "aws_lb" "main" {
  name               = "${local.prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  enable_deletion_protection = false

  tags = merge(local.common_tags, { Name = "${local.prefix}-alb" })
}

# ── Target groups (one per service) ───────────────────────────────────────────

locals {
  target_groups = {
    "user-service"         = { port = 8081, health_path = "/actuator/health" }
    "venue-service"        = { port = 8082, health_path = "/actuator/health" }
    "booking-service"      = { port = 8083, health_path = "/actuator/health" }
    "notification-service" = { port = 8084, health_path = "/actuator/health" }
  }
}

resource "aws_lb_target_group" "services" {
  for_each = local.target_groups

  name        = "${local.prefix}-${substr(each.key, 0, 18)}-tg"
  port        = each.value.port
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip" # Required for Fargate

  health_check {
    path                = each.value.health_path
    interval            = 30
    timeout             = 10
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }

  tags = merge(local.common_tags, { Name = "${local.prefix}-${each.key}-tg" })
}

# ── HTTP Listener with path-based routing ─────────────────────────────────────

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  # Default action: 404 for unmatched paths
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "application/json"
      message_body = "{\"error\":\"not found\"}"
      status_code  = "404"
    }
  }
}

# Listener rules — priority order matters (lower = higher priority)

resource "aws_lb_listener_rule" "user_service" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services["user-service"].arn
  }

  condition {
    path_pattern { values = ["/api/v1/auth/*"] }
  }
}

resource "aws_lb_listener_rule" "venue_service" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 20

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services["venue-service"].arn
  }

  condition {
    path_pattern { values = ["/api/v1/venues/*", "/api/v1/admin/*"] }
  }
}

resource "aws_lb_listener_rule" "booking_service_holds" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 30

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services["booking-service"].arn
  }

  condition {
    path_pattern { values = ["/api/v1/holds/*", "/api/v1/holds"] }
  }
}

resource "aws_lb_listener_rule" "booking_service_bookings" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 40

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services["booking-service"].arn
  }

  condition {
    path_pattern { values = ["/api/v1/bookings/*", "/api/v1/bookings"] }
  }
}
