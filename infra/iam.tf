locals {
  task_trust = {
    for key, repo in aws_ecr_repository.service : key => jsonencode({
      Version = "2012-10-17", Statement = [{
        Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ecs-tasks.amazonaws.com" },
        Condition = {
          StringEquals = { "aws:SourceAccount" = repo.registry_id },
          ArnLike      = { "aws:SourceArn" = "arn:aws:ecs:${var.aws_region}:${repo.registry_id}:*" }
        }
      }]
    })
  }
}
resource "aws_iam_role" "execution" {
  for_each           = local.services
  name               = "${var.name}-${each.key}-execution"
  assume_role_policy = local.task_trust[each.key]
}
resource "aws_iam_role_policy" "execution" {
  for_each = local.services
  role     = aws_iam_role.execution[each.key].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    # ECR authorization does not support resource-level permissions.
    { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
    { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = aws_ecr_repository.service[each.key].arn },
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.service[each.key].arn}:*" }
  ] })
}
resource "aws_iam_role" "task" {
  for_each           = local.services
  name               = "${var.name}-${each.key}-task"
  assume_role_policy = local.task_trust[each.key]
}
resource "aws_iam_role_policy" "ingestion" {
  role = aws_iam_role.task["ingestion"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect = "Allow", Action = ["kinesis:PutRecord", "kinesis:DescribeStreamSummary"], Resource = aws_kinesis_stream.events.arn
  }] })
}
resource "aws_iam_role_policy" "analytics" {
  role = aws_iam_role.task["analytics"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["kinesis:DescribeStream", "kinesis:DescribeStreamSummary", "kinesis:GetRecords", "kinesis:GetShardIterator", "kinesis:ListShards"], Resource = aws_kinesis_stream.events.arn },
    { Effect = "Allow", Action = ["dynamodb:DescribeTable"], Resource = [for table in aws_dynamodb_table.application : table.arn] },
    { Effect = "Allow", Action = ["dynamodb:Query", "dynamodb:UpdateItem"], Resource = aws_dynamodb_table.application["analytics"].arn },
    { Effect = "Allow", Action = ["dynamodb:GetItem", "dynamodb:PutItem"], Resource = aws_dynamodb_table.application["processed-events"].arn },
    { Effect = "Allow", Action = ["dynamodb:PutItem"], Resource = aws_dynamodb_table.application["quarantine"].arn },
    # Precreated KCL tables/index need item access, not table creation/deletion or migration rights.
    { Effect = "Allow", Action = ["dynamodb:DescribeTable", "dynamodb:Scan", "dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:DeleteItem"], Resource = concat([aws_dynamodb_table.kcl_lease.arn], [for table in aws_dynamodb_table.kcl_state : table.arn]) },
    { Effect = "Allow", Action = ["dynamodb:Query"], Resource = "${aws_dynamodb_table.kcl_lease.arn}/index/LeaseOwnerToLeaseKeyIndex" },
    # PutMetricData has no resource ARN; constrain the actual ConfigsBuilder namespace instead.
    { Effect = "Allow", Action = ["cloudwatch:PutMetricData"], Resource = "*", Condition = { StringEquals = { "cloudwatch:namespace" = local.kcl_application } } }
  ] })
}
