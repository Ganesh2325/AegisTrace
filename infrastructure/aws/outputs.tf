output "documents_bucket" {
  value = aws_s3_bucket.documents.bucket
}

output "cluster" {
  value = aws_ecs_cluster.main.name
}

output "load_balancer" {
  value = aws_lb.edge.dns_name
}

output "database_address" {
  value = aws_db_instance.postgres.address
}

output "deploy_role_arn" {
  value = aws_iam_role.deploy.arn
}

output "repository_urls" {
  value = { for name, repository in aws_ecr_repository.services : name => repository.repository_url }
}
