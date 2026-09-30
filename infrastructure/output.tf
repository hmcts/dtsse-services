output "postgresql_fqdn" {
  value = one(module.postgresql[*].fqdn)
}

output "workload_identity_client_id" {
  value = one(azurerm_user_assigned_identity.dtsse_services[*].client_id)
}

output "workload_identity_principal_id" {
  value = one(azurerm_user_assigned_identity.dtsse_services[*].principal_id)
}
