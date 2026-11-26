data "aws_caller_identity" "current" {}

data "aws_ecr_repository" "app" {
  name = var.project_name
}

# --- S3 Bucket for Exported CSV Reports ---
resource "random_id" "bucket_suffix" {
  byte_length = 4
}

resource "aws_s3_bucket" "exports" {
  bucket        = "${local.name_prefix}-exports-${random_id.bucket_suffix.hex}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "exports" {
  bucket                  = aws_s3_bucket.exports.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "exports" {
  bucket = aws_s3_bucket.exports.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "exports" {
  bucket = aws_s3_bucket.exports.id

  rule {
    id     = "expire-exports-after-7-days"
    status = "Enabled"

    filter {
      prefix = "exports/"
    }

    expiration {
      days = 7
    }
  }
}

# --- SQS Main Queue + Dead Letter Queue (DLQ) ---
resource "aws_sqs_queue" "dlq" {
  name                      = "${local.name_prefix}-jobs-dlq"
  message_retention_seconds = 1209600 # 14 days
}

resource "aws_sqs_queue" "main" {
  name                       = "${local.name_prefix}-jobs"
  visibility_timeout_seconds = 90
  receive_wait_time_seconds  = 20 # Long polling
  message_retention_seconds  = 345600

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = 3
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  queue_url = aws_sqs_queue.dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.main.arn]
  })
}

# --- RDS PostgreSQL 16 (Private Subnets) + SSM Parameter Store (Standard Tier = Free) ---
resource "random_password" "db" {
  length  = 24
  special = false
}

resource "aws_ssm_parameter" "db_password" {
  name        = "/${var.project_name}/${var.env}/db-password"
  description = "PostgreSQL master password injected into ECS tasks at runtime"
  type        = "SecureString"
  value       = random_password.db.result
}

resource "aws_db_subnet_group" "postgres" {
  name       = "${local.name_prefix}-db-subnets"
  subnet_ids = aws_subnet.private[*].id

  tags = {
    Name = "${local.name_prefix}-db-subnets"
  }
}

resource "aws_db_instance" "postgres" {
  identifier             = "${local.name_prefix}-pg"
  engine                 = "postgres"
  engine_version         = "16"
  instance_class         = "db.t4g.micro"
  allocated_storage      = 20
  storage_type           = "gp3"
  storage_encrypted      = true
  db_name                = "cloudjob"
  username               = "cloudjob"
  password               = random_password.db.result
  db_subnet_group_name   = aws_db_subnet_group.postgres.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  # Dev cost/cleanup hygiene: fast teardown without lingering paid snapshots
  backup_retention_period = 0
  skip_final_snapshot     = true
  deletion_protection     = false
}

resource "aws_ssm_parameter" "db_url" {
  name  = "/${var.project_name}/${var.env}/db-url"
  type  = "String"
  value = "jdbc:postgresql://${aws_db_instance.postgres.endpoint}/${aws_db_instance.postgres.db_name}?sslmode=require"
}
