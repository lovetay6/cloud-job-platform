output "alb_base_url" {
  value = "http://${aws_lb.api.dns_name}"
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "ecs_api_service_name" {
  value = aws_ecs_service.api.name
}

output "ecs_worker_service_name" {
  value = aws_ecs_service.worker.name
}

output "s3_exports_bucket" {
  value = aws_s3_bucket.exports.bucket
}

output "sqs_main_queue_url" {
  value = aws_sqs_queue.main.url
}

output "sqs_dlq_url" {
  value = aws_sqs_queue.dlq.url
}

output "rds_endpoint" {
  value = aws_db_instance.postgres.endpoint
}

output "cloudwatch_log_group" {
  value = aws_cloudwatch_log_group.ecs.name
}
