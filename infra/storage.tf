resource "aws_kinesis_stream" "events" {
  name             = "${var.name}-events"
  shard_count      = var.shard_count
  retention_period = 24
  encryption_type  = "KMS"
  kms_key_id       = "alias/aws/kinesis"
  stream_mode_details { stream_mode = "PROVISIONED" }
}
resource "aws_dynamodb_table" "application" {
  for_each     = toset(["analytics", "processed-events", "quarantine"])
  name         = "${var.name}-${each.key}"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"
  attribute {
    name = "PK"
    type = "S"
  }
  attribute {
    name = "SK"
    type = "S"
  }
  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }
  server_side_encryption { enabled = true }
  on_demand_throughput {
    max_read_request_units  = var.dynamodb_max_read_units
    max_write_request_units = var.dynamodb_max_write_units
  }
}
# KCL 3.4.3's three-table layout; Terraform owns table lifecycle, KCL owns the items.
resource "aws_dynamodb_table" "kcl_lease" {
  name         = "${var.name}-kcl-leases"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "leaseKey"
  attribute {
    name = "leaseKey"
    type = "S"
  }
  attribute {
    name = "leaseOwner"
    type = "S"
  }
  global_secondary_index {
    name            = "LeaseOwnerToLeaseKeyIndex"
    hash_key        = "leaseOwner"
    range_key       = "leaseKey"
    projection_type = "KEYS_ONLY"
    on_demand_throughput {
      max_read_request_units  = var.dynamodb_max_read_units
      max_write_request_units = var.dynamodb_max_write_units
    }
  }
  server_side_encryption { enabled = true }
  on_demand_throughput {
    max_read_request_units  = var.dynamodb_max_read_units
    max_write_request_units = var.dynamodb_max_write_units
  }
}
resource "aws_dynamodb_table" "kcl_state" {
  for_each     = { worker = "wid", coordinator = "key" }
  name         = "${var.name}-kcl-${each.key}"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = each.value
  attribute {
    name = each.value
    type = "S"
  }
  server_side_encryption { enabled = true }
  on_demand_throughput {
    max_read_request_units  = var.dynamodb_max_read_units
    max_write_request_units = var.dynamodb_max_write_units
  }
}
resource "aws_ecr_repository" "service" {
  for_each             = local.services
  name                 = "${var.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
}
resource "aws_ecr_lifecycle_policy" "service" {
  for_each   = local.services
  repository = aws_ecr_repository.service[each.key].name
  policy = jsonencode({ rules = [{
    rulePriority = 1, description = "Expire untagged build leftovers after seven days",
    selection    = { tagStatus = "untagged", countType = "sinceImagePushed", countUnit = "days", countNumber = 7 },
    action       = { type = "expire" }
  }] })
}
resource "aws_cloudwatch_log_group" "service" {
  for_each          = local.services
  name              = "/ecs/${var.name}/${each.key}"
  retention_in_days = 7
}
