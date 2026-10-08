resource "aws_cloudwatch_log_group" "app" {
  name              = "/${var.name}/${var.environment}"
  retention_in_days = 30
}

resource "aws_ecs_cluster" "main" {
  name = "${var.name}-${var.environment}"
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "aegistrace.local"
  vpc  = aws_vpc.main.id
}

resource "aws_service_discovery_service" "control" {
  name = "control-plane"
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.main.id
    routing_policy = "MULTIVALUE"
    dns_records {
      ttl  = 10
      type = "A"
    }
  }
  health_check_custom_config {
    failure_threshold = 1
  }
}

resource "aws_service_discovery_service" "runtime" {
  name = "runtime"
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.main.id
    routing_policy = "MULTIVALUE"
    dns_records {
      ttl  = 10
      type = "A"
    }
  }
  health_check_custom_config {
    failure_threshold = 1
  }
}

locals {
  log_config = {
    logDriver = "awslogs"
    options = {
      awslogs-group         = aws_cloudwatch_log_group.app.name
      awslogs-region        = var.region
      awslogs-stream-prefix = "app"
    }
  }
}

resource "aws_ecs_task_definition" "frontend" {
  family                   = "${var.name}-${var.environment}-frontend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.execution.arn
  container_definitions = jsonencode([{
    name         = "frontend"
    image        = var.frontend_image
    essential    = true
    portMappings = [{ containerPort = 3000, protocol = "tcp" }]
    environment = [
      { name = "API_INTERNAL_URL", value = "http://control-plane.aegistrace.local:8080" },
      { name = "NODE_ENV", value = "production" }
    ]
    logConfiguration = local.log_config
  }])
}

resource "aws_ecs_task_definition" "control" {
  family                   = "${var.name}-${var.environment}-control"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.control.arn
  container_definitions = jsonencode([{
    name         = "control-plane"
    image        = var.control_plane_image
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }, { containerPort = 8081, protocol = "tcp" }]
    environment = [
      { name = "AEGIS_ENVIRONMENT", value = "prod" },
      { name = "AEGIS_SEED_ENABLED", value = "false" },
      { name = "AEGIS_FAILURE_SIMULATION", value = "false" },
      { name = "AEGIS_COOKIE_SECURE", value = "true" },
      { name = "FRONTEND_ORIGIN", value = var.frontend_origin },
      { name = "AEGIS_RUNTIME_URL", value = "http://runtime.aegistrace.local:8090" },
      { name = "REDIS_ENABLED", value = "true" },
      { name = "REDIS_HOST", value = aws_elasticache_replication_group.redis.primary_endpoint_address },
      { name = "REDIS_PORT", value = "6379" },
      { name = "S3_BUCKET", value = aws_s3_bucket.documents.bucket },
      { name = "S3_REGION", value = var.region },
      { name = "S3_ENDPOINT", value = "" },
      { name = "AEGIS_RELEASE_SHA", value = var.release_sha },
      { name = "AEGIS_IMAGE_DIGEST", value = var.control_plane_image },
      { name = "DATABASE_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/aegistrace" },
      { name = "POSTGRES_USER", value = "aegis_migrate" }
    ]
    secrets = [
      { name = "AEGIS_JWT_SECRET", valueFrom = "${aws_secretsmanager_secret.app.arn}:jwt_secret::" },
      { name = "AEGIS_INTERNAL_TOKEN", valueFrom = "${aws_secretsmanager_secret.app.arn}:internal_token::" },
      { name = "POSTGRES_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app.arn}:app_password::" },
      { name = "REDIS_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app.arn}:redis_auth_token::" }
    ]
    logConfiguration = local.log_config
  }])
}

resource "aws_ecs_task_definition" "runtime" {
  family                   = "${var.name}-${var.environment}-runtime"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.runtime.arn
  container_definitions = jsonencode([{
    name      = "runtime"
    image     = var.runtime_image
    essential = true
    portMappings = [{ containerPort = 8090, protocol = "tcp" }]
    environment = [
      { name = "AEGIS_ENVIRONMENT", value = "prod" },
      { name = "DATABASE_URL", value = "postgresql://aegis_app@${aws_db_instance.postgres.address}:5432/aegistrace" }
    ]
    secrets = [
      { name = "AEGIS_INTERNAL_TOKEN", valueFrom = "${aws_secretsmanager_secret.app.arn}:internal_token::" },
      { name = "DATABASE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app.arn}:app_password::" }
    ]
    logConfiguration = local.log_config
  }])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${var.name}-${var.environment}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.worker.arn
  container_definitions = jsonencode([{
    name      = "worker"
    image     = var.worker_image
    essential = true
    environment = [
      { name = "AEGIS_ENVIRONMENT", value = "prod" },
      { name = "SEED_KNOWLEDGE", value = "false" },
      { name = "CONTROL_PLANE_URL", value = "http://control-plane.aegistrace.local:8080" },
      { name = "S3_BUCKET", value = aws_s3_bucket.documents.bucket },
      { name = "S3_REGION", value = var.region },
      { name = "S3_ENDPOINT", value = "" },
      { name = "DATABASE_URL", value = "postgresql://aegis_app@${aws_db_instance.postgres.address}:5432/aegistrace" }
    ]
    secrets = [
      { name = "AEGIS_INTERNAL_TOKEN", valueFrom = "${aws_secretsmanager_secret.app.arn}:internal_token::" },
      { name = "DATABASE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app.arn}:app_password::" }
    ]
    logConfiguration = local.log_config
  }])
}

resource "aws_ecs_service" "frontend" {
  name            = "frontend"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.frontend.arn
  desired_count   = 1
  launch_type     = "FARGATE"
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = [aws_subnet.private_a.id, aws_subnet.private_b.id]
    security_groups  = [aws_security_group.frontend.id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.frontend.arn
    container_name   = "frontend"
    container_port   = 3000
  }
  depends_on = [aws_lb_listener.https]
}

resource "aws_ecs_service" "control" {
  name            = "control-plane"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.control.arn
  desired_count   = 1
  launch_type     = "FARGATE"
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = [aws_subnet.private_a.id, aws_subnet.private_b.id]
    security_groups  = [aws_security_group.control.id]
    assign_public_ip = false
  }
  service_registries {
    registry_arn = aws_service_discovery_service.control.arn
  }
}

resource "aws_ecs_service" "runtime" {
  name            = "runtime"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.runtime.arn
  desired_count   = 1
  launch_type     = "FARGATE"
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = [aws_subnet.private_a.id, aws_subnet.private_b.id]
    security_groups  = [aws_security_group.runtime.id]
    assign_public_ip = false
  }
  service_registries {
    registry_arn = aws_service_discovery_service.runtime.arn
  }
}

resource "aws_ecs_service" "worker" {
  name            = "worker"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = 1
  launch_type     = "FARGATE"
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = [aws_subnet.private_a.id, aws_subnet.private_b.id]
    security_groups  = [aws_security_group.worker.id]
    assign_public_ip = false
  }
}
