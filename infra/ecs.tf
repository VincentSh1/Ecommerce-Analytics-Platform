resource "aws_ecs_cluster" "main" {
  name = var.name
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}
locals {
  common_environment = {
    AWS_REGION                = var.aws_region
    APP_ENVIRONMENT           = var.environment
    SERVER_ADDRESS            = "0.0.0.0"
    KINESIS_STREAM            = aws_kinesis_stream.events.name
    CORS_ORIGINS              = "https://${aws_cloudfront_distribution.frontend.domain_name}"
    AWS_EC2_METADATA_DISABLED = "true"
  }
  analytics_environment = {
    ANALYTICS_TABLE          = aws_dynamodb_table.application["analytics"].name
    PROCESSED_TABLE          = aws_dynamodb_table.application["processed-events"].name
    QUARANTINE_TABLE         = aws_dynamodb_table.application["quarantine"].name
    DATASET_ID               = var.dataset_id
    AGGREGATE_STRIPES        = "16"
    KCL_APPLICATION          = local.kcl_application
    KCL_LEASE_TABLE          = aws_dynamodb_table.kcl_lease.name
    KCL_WORKER_METRICS_TABLE = aws_dynamodb_table.kcl_state["worker"].name
    KCL_COORDINATOR_TABLE    = aws_dynamodb_table.kcl_state["coordinator"].name
    KCL_WORKER_METRIC        = "AUTO"
    CLOUDWATCH_METRICS       = "true"
  }
}
resource "aws_ecs_task_definition" "service" {
  for_each                 = local.active_services
  family                   = "${var.name}-${each.key}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = tostring(var.task_cpu)
  memory                   = tostring(var.task_memory)
  execution_role_arn       = aws_iam_role.execution[each.key].arn
  task_role_arn            = aws_iam_role.task[each.key].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }
  container_definitions = jsonencode([{
    name      = each.key
    image     = "${aws_ecr_repository.service[each.key].repository_url}@${var.image_digests[each.key]}"
    essential = true
    user      = "10001:10001"
    # Existing images need writable /tmp. Fargate cannot use Compose's tmpfs setting.
    readonlyRootFilesystem = false
    privileged             = false
    linuxParameters        = { initProcessEnabled = true, capabilities = { drop = ["ALL"] } }
    stopTimeout            = 30
    portMappings           = [{ containerPort = each.value.port, hostPort = each.value.port, protocol = "tcp" }]
    environment = [for name, value in merge(
      local.common_environment,
      each.key == "analytics" ? local.analytics_environment : {},
      { SERVER_PORT = tostring(each.value.port), HTTP_RATE = tostring(each.value.rate), HTTP_IN_FLIGHT = tostring(each.value.in_flight) }
    ) : { name = name, value = value }]
    healthCheck = {
      command  = ["CMD-SHELL", "curl --fail --silent --max-time 4 http://127.0.0.1:${each.value.port}/actuator/health/liveness >/dev/null || exit 1"]
      interval = 30, timeout = 5, retries = 3, startPeriod = 60
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service[each.key].name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "ecs"
        mode                  = "non-blocking"
        max-buffer-size       = "10m"
      }
    }
  }])
}
resource "aws_ecs_service" "service" {
  for_each                           = local.active_services
  name                               = each.key
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.service[each.key].arn
  desired_count                      = var.desired_count
  launch_type                        = "FARGATE"
  platform_version                   = "1.4.0"
  enable_execute_command             = false
  health_check_grace_period_seconds  = 300
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  wait_for_steady_state              = true
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = [for subnet in aws_subnet.public : subnet.id]
    security_groups  = [aws_security_group.task[each.key].id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.service[each.key].arn
    container_name   = each.key
    container_port   = each.value.port
  }
  depends_on = [
    aws_lb_listener_rule.api, aws_route.internet, aws_route_table_association.public,
    aws_iam_role_policy.execution, aws_iam_role_policy.ingestion, aws_iam_role_policy.analytics,
    aws_s3_bucket_policy.frontend
  ]
}
