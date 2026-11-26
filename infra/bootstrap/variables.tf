variable "aws_region" {
  description = "AWS region for persistent bootstrap resources"
  type        = string
  default     = "ap-southeast-2"
}

variable "aws_profile" {
  description = "Local AWS CLI profile (bridged via credential_process to aws login)"
  type        = string
  default     = "tf"
}

variable "project_name" {
  description = "Project prefix used for ECR repo and IAM naming"
  type        = string
  default     = "cloud-job-platform"
}
