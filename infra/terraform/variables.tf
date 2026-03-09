variable "aws_region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment label"
  type        = string
  default     = "prod"
}

variable "db_username" {
  description = "Master username for all RDS instances"
  type        = string
  default     = "seatlock"
}

variable "db_password" {
  description = "Master password for all RDS instances (sensitive)"
  type        = string
  sensitive   = true
}

variable "app_version" {
  description = "Docker image tag to deploy (e.g. 'latest' or a git SHA)"
  type        = string
  default     = "latest"
}

variable "desired_count" {
  description = "Desired number of ECS tasks per service"
  type        = number
  default     = 1
}

variable "smtp_from_address" {
  description = "From address for notification emails"
  type        = string
  default     = "noreply@example.com"
}

variable "smtp_default_recipient" {
  description = "Default recipient for notification emails (dev/demo only)"
  type        = string
  default     = "user@example.com"
}
