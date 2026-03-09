resource "aws_db_subnet_group" "main" {
  name       = "${local.prefix}-db-subnet-group"
  subnet_ids = aws_subnet.private[*].id
  tags       = merge(local.common_tags, { Name = "${local.prefix}-db-subnet-group" })
}

# ── One RDS instance per service (true DB isolation) ──────────────────────────

locals {
  rds_instances = {
    user    = { db_name = "user_db",    port = 8081 }
    venue   = { db_name = "venue_db",   port = 8082 }
    booking = { db_name = "booking_db", port = 8083 }
  }
}

resource "aws_db_instance" "services" {
  for_each = local.rds_instances

  identifier        = "${local.prefix}-${each.key}-db"
  engine            = "postgres"
  engine_version    = "15"
  instance_class    = "db.t3.micro"
  allocated_storage = 20
  storage_type      = "gp2"

  db_name  = each.value.db_name
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  # Not publicly accessible — only reachable from within the VPC
  publicly_accessible = false

  # Automated backups
  backup_retention_period = 7
  backup_window           = "03:00-04:00"
  maintenance_window      = "Mon:04:00-Mon:05:00"

  # Performance Insights (free tier for db.t3.micro)
  performance_insights_enabled = false

  # Skip final snapshot for easy destroy (set to false for real production)
  skip_final_snapshot = true
  deletion_protection = false

  tags = merge(local.common_tags, {
    Name    = "${local.prefix}-${each.key}-db"
    Service = each.key
  })
}
