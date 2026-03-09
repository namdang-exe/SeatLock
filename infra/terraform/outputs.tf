output "alb_dns_name" {
  description = "Public DNS name of the ALB — use this to test the API"
  value       = aws_lb.main.dns_name
}

output "ecr_urls" {
  description = "ECR repository URLs for Docker push"
  value       = { for k, v in aws_ecr_repository.services : k => v.repository_url }
}

output "rds_endpoints" {
  description = "RDS endpoint addresses (private — accessible only from within VPC)"
  value = {
    user    = aws_db_instance.services["user"].address
    venue   = aws_db_instance.services["venue"].address
    booking = aws_db_instance.services["booking"].address
  }
}

output "redis_host" {
  description = "ElastiCache Redis endpoint (private)"
  value       = aws_elasticache_cluster.redis.cache_nodes[0].address
}

output "sqs_queue_url" {
  description = "SQS event queue URL"
  value       = aws_sqs_queue.events.url
}

output "ecs_cluster_name" {
  description = "ECS cluster name"
  value       = aws_ecs_cluster.main.name
}

output "secret_arns" {
  description = "ARNs of Secrets Manager secrets — populate these after apply"
  value = {
    jwt_private_key    = aws_secretsmanager_secret.jwt_private_key.arn
    jwt_public_key     = aws_secretsmanager_secret.jwt_public_key.arn
    service_jwt_secret = aws_secretsmanager_secret.service_jwt_secret.arn
    smtp_username      = aws_secretsmanager_secret.smtp_username.arn
    smtp_password      = aws_secretsmanager_secret.smtp_password.arn
  }
}
