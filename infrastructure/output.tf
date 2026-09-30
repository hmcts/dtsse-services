output "postgresql_fqdn" {
  value = one(module.postgresql[*].fqdn)
}

output "managed_identity_client_id" {
  value = one(flatten(module.key_vault[*].managed_identity_clientid))
}

output "managed_identity_principal_id" {
  value = one(flatten(module.key_vault[*].managed_identity_objectid))
}
