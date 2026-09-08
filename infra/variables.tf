variable "name" {
  type    = string
  default = "commerce-demo"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,19}$", var.name))
    error_message = "Use a 3–20 character lowercase resource prefix."
  }
}
variable "environment" {
  type    = string
  default = "demo"
}
variable "aws_region" {
  type = string
  validation {
    condition     = can(regex("^[a-z]{2}-[a-z]+-[0-9]+$", var.aws_region))
    error_message = "Supply a commercial AWS region."
  }
}
variable "availability_zones" {
  type = list(string)
  validation {
    condition     = length(toset(var.availability_zones)) == 2 && length(var.availability_zones) == 2 && alltrue([for az in var.availability_zones : startswith(az, var.aws_region)])
    error_message = "Supply two distinct availability zones in the selected region."
  }
}
variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
  validation {
    condition     = can(cidrnetmask(var.vpc_cidr)) && can(regex("/16$", var.vpc_cidr))
    error_message = "Supply an IPv4 /16 VPC CIDR."
  }
}
variable "operator_cidrs" {
  type = set(string)
  validation {
    condition     = length(var.operator_cidrs) > 0 && alltrue([for cidr in var.operator_cidrs : can(cidrnetmask(cidr)) && can(regex("/(2[4-9]|3[0-2])$", cidr))])
    error_message = "Explicit IPv4 operator/load-test CIDRs of /24 or narrower are required; unrestricted ingress is forbidden."
  }
}
variable "api_hostname" {
  type = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}$", var.api_hostname))
    error_message = "Supply the DNS hostname covered by the API certificate, without scheme or path."
  }
}
variable "api_certificate_arn" {
  type = string
  validation {
    condition     = can(regex("^arn:aws:acm:${var.aws_region}:[0-9]{12}:certificate/[a-z0-9-]+$", var.api_certificate_arn))
    error_message = "Supply an existing issued ACM certificate ARN in the selected region."
  }
}
variable "dataset_id" {
  type    = string
  default = "aws-v1"
  validation {
    condition     = can(regex("^[a-zA-Z0-9_.-]{1,64}$", var.dataset_id))
    error_message = "Dataset must match the application's bounded identifier contract."
  }
}
variable "enable_services" {
  type        = bool
  default     = false
  description = "Enable only after real image digests exist in the provisioned ECR repositories. Other resources still incur charges when this is false."
  validation {
    condition     = !var.enable_services || (contains(keys(var.image_digests), "ingestion") && contains(keys(var.image_digests), "analytics"))
    error_message = "Both image digests are required before enabling ECS tasks/services."
  }
}
variable "image_digests" {
  type    = map(string)
  default = {}
  validation {
    condition     = alltrue([for key, digest in var.image_digests : contains(["ingestion", "analytics"], key) && can(regex("^sha256:[0-9a-f]{64}$", digest))])
    error_message = "Use ingestion/analytics keys and actual sha256 image digests; no tags or placeholders."
  }
}
variable "cpu_architecture" {
  type    = string
  default = "ARM64"
  validation {
    condition     = contains(["ARM64", "X86_64"], var.cpu_architecture)
    error_message = "Choose ARM64 or X86_64 and build matching images."
  }
}
variable "desired_count" {
  type    = number
  default = 1
  validation {
    condition     = contains([1, 2], var.desired_count)
    error_message = "The bounded demo supports one or two tasks per service."
  }
}
variable "task_cpu" {
  type    = number
  default = 1024
  validation {
    condition     = contains([512, 1024], var.task_cpu)
    error_message = "Use 512 or 1024 CPU units for this demo."
  }
}
variable "task_memory" {
  type    = number
  default = 2048
  validation {
    condition     = contains([2048, 3072, 4096], var.task_memory)
    error_message = "Use 2048, 3072, or 4096 MiB (valid with either allowed CPU size)."
  }
}
variable "shard_count" {
  type    = number
  default = 1
  validation {
    condition     = contains([1, 2, 3, 4], var.shard_count)
    error_message = "Choose 1–4 provisioned shards; this is a cost bound, not a capacity result."
  }
}
variable "dynamodb_max_read_units" {
  type    = number
  default = 100
  validation {
    condition     = var.dynamodb_max_read_units >= 1 && floor(var.dynamodb_max_read_units) == var.dynamodb_max_read_units
    error_message = "Supply a positive integer on-demand read ceiling."
  }
}
variable "dynamodb_max_write_units" {
  type    = number
  default = 100
  validation {
    condition     = var.dynamodb_max_write_units >= 1 && floor(var.dynamodb_max_write_units) == var.dynamodb_max_write_units
    error_message = "Supply a positive integer on-demand write ceiling."
  }
}
