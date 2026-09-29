output "postgresql_fqdn" {
  value = var.env == "prod" ? module.postgresql[0].fqdn : null
}

output "postgresql_password" {
  value     = var.env == "prod" ? module.postgresql[0].password : null
  sensitive = true
}
