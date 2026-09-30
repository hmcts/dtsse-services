output "postgresql_fqdn" {
  value = one(module.postgresql[*].fqdn)
}
