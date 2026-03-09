terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Local backend — state stored in terraform.tfstate (gitignored).
  # To migrate to S3: uncomment the block below, create the bucket first,
  # then run 'terraform init -migrate-state'.
  #
  # backend "s3" {
  #   bucket         = "seatlock-terraform-state"
  #   key            = "prod/terraform.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "seatlock-terraform-locks"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.name
  prefix     = "seatlock"

  common_tags = {
    Project     = "SeatLock"
    Environment = var.environment
    ManagedBy   = "Terraform"
  }
}
