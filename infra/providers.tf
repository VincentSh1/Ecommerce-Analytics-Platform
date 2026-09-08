terraform {
  required_version = "= 1.16.1"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.63.0"
    }
  }
  # Local state is intentional for offline validation. Protect/migrate state before deployment.
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = { Project = var.name, Environment = var.environment, ManagedBy = "Terraform" }
  }
}
