terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
  }
}

provider "aws" {
  region = var.region
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "name" {
  type    = string
  default = "aegistrace"
}

variable "db_password" {
  type      = string
  sensitive = true
}

# This stack is the production-like shape. It is not applied by CI.
# pgvector is available on RDS for PostgreSQL 16. Apply only with a real account
# and replace every default secret through Secrets Manager.

resource "aws_vpc" "main" {
  cidr_block           = "10.40.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "${var.name}-vpc" }
}

resource "aws_subnet" "a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.40.1.0/24"
  availability_zone = "${var.region}a"
}

resource "aws_subnet" "b" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.40.2.0/24"
  availability_zone = "${var.region}b"
}

resource "aws_db_subnet_group" "db" {
  name       = "${var.name}-db"
  subnet_ids = [aws_subnet.a.id, aws_subnet.b.id]
}

resource "aws_security_group" "db" {
  name   = "${var.name}-db"
  vpc_id = aws_vpc.main.id
  ingress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = ["10.40.0.0/16"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_db_instance" "postgres" {
  identifier             = "${var.name}-postgres"
  engine                 = "postgres"
  engine_version         = "16.4"
  instance_class         = "db.t4g.micro"
  allocated_storage      = 20
  db_name                = "aegistrace"
  username               = "aegis"
  password               = var.db_password
  db_subnet_group_name   = aws_db_subnet_group.db.name
  vpc_security_group_ids = [aws_security_group.db.id]
  skip_final_snapshot    = true
  publicly_accessible    = false
}

resource "aws_elasticache_subnet_group" "redis" {
  name       = "${var.name}-redis"
  subnet_ids = [aws_subnet.a.id, aws_subnet.b.id]
}

resource "aws_elasticache_cluster" "redis" {
  cluster_id           = "${var.name}-redis"
  engine               = "redis"
  node_type            = "cache.t4g.micro"
  num_cache_nodes      = 1
  subnet_group_name    = aws_elasticache_subnet_group.redis.name
  parameter_group_name = "default.redis7"
}

resource "aws_s3_bucket" "documents" {
  bucket_prefix = "${var.name}-documents-"
}

resource "aws_secretsmanager_secret" "app" {
  name = "${var.name}/app"
}

resource "aws_ecs_cluster" "main" {
  name = var.name
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/${var.name}/app"
  retention_in_days = 30
}

output "documents_bucket" { value = aws_s3_bucket.documents.bucket }
output "ecs_cluster" { value = aws_ecs_cluster.main.name }
output "postgres_endpoint" { value = aws_db_instance.postgres.address }
