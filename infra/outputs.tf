output "api_url" {
  value       = local.api_url
  description = "Intended URL; requires operator-managed DNS pointing at alb_dns_name."
}
output "alb_dns_name" { value = aws_lb.api.dns_name }
output "alb_zone_id" { value = aws_lb.api.zone_id }
output "frontend_url" { value = "https://${aws_cloudfront_distribution.frontend.domain_name}" }
output "frontend_bucket" { value = aws_s3_bucket.frontend.id }
output "frontend_distribution_id" { value = aws_cloudfront_distribution.frontend.id }
output "ecr_repositories" { value = { for key, repo in aws_ecr_repository.service : key => repo.repository_url } }
output "frontend_build_environment" {
  value = {
    VITE_API_BASE_URL      = local.api_url
    VITE_READINESS_ENABLED = "false"
  }
  description = "Public build-time configuration only. This output does not upload assets."
}
