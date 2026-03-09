# Private DNS namespace for inter-service discovery: *.seatlock.local
resource "aws_service_discovery_private_dns_namespace" "main" {
  name        = "seatlock.local"
  description = "Private DNS namespace for SeatLock service discovery"
  vpc         = aws_vpc.main.id
  tags        = merge(local.common_tags, { Name = "${local.prefix}-namespace" })
}

locals {
  discovery_services = {
    "user-service"         = { port = 8081 }
    "venue-service"        = { port = 8082 }
    "booking-service"      = { port = 8083 }
    "notification-service" = { port = 8084 }
  }
}

resource "aws_service_discovery_service" "services" {
  for_each = local.discovery_services

  name = each.key

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.main.id
    routing_policy = "MULTIVALUE"

    dns_records {
      ttl  = 10
      type = "A"
    }
  }

  health_check_custom_config {
    failure_threshold = 1
  }

  tags = merge(local.common_tags, { Name = "${local.prefix}-${each.key}-discovery" })
}
