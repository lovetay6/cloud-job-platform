variable "aws_region" {
  description = "AWS region (ap-southeast-2 under Free Plan)"
  type        = string
  default     = "ap-southeast-2"
}

variable "aws_profile" {
  description = "Local AWS CLI profile (set empty string if using env vars in CI)"
  type        = string
  default     = "tf"
}

variable "project_name" {
  type    = string
  default = "cloud-job-platform"
}

variable "env" {
  type    = string
  default = "dev"
}

variable "image_tag" {
  description = "Docker image tag in ECR deployed to ECS Fargate"
  type        = string
  default     = "latest"
}

variable "ecs_desired_count" {
  description = "Number of running tasks per ECS service (set 0 to pause compute without destroying data)"
  type        = number
  default     = 1
}

variable "enable_nat_gateway" {
  description = "Set true to place ECS tasks in private subnets behind a NAT Gateway (~$0.045/hr); false places ECS in public subnets locked strictly to ALB SG"
  type        = bool
  default     = false
}
