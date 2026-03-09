locals {
  services = ["user-service", "venue-service", "booking-service", "notification-service"]
}

resource "aws_ecr_repository" "services" {
  for_each             = toset(local.services)
  name                 = "${local.prefix}/${each.key}"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = merge(local.common_tags, { Name = "${local.prefix}-${each.key}" })
}

# Lifecycle policy: keep the last 10 images per repository
resource "aws_ecr_lifecycle_policy" "services" {
  for_each   = aws_ecr_repository.services
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep last 10 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
