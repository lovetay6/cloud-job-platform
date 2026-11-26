output "aws_account_id" {
  value = data.aws_caller_identity.current.account_id
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "ecr_repository_name" {
  value = aws_ecr_repository.app.name
}

output "ci_access_key_id" {
  value     = aws_iam_access_key.ci_deployer.id
  sensitive = true
}

output "ci_secret_access_key" {
  value     = aws_iam_access_key.ci_deployer.secret
  sensitive = true
}
