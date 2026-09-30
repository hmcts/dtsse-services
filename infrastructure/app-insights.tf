module "application_insights" {
  count = local.create_infrastructure ? 1 : 0

  source              = "git@github.com:hmcts/terraform-module-application-insights?ref=5.x"
  env                 = var.env
  product             = var.product
  name                = "${var.product}-appinsights"
  resource_group_name = azurerm_resource_group.rg[0].name
  common_tags         = var.common_tags
}

resource "azurerm_key_vault_secret" "app_insights_connection_string" {
  count = local.create_infrastructure ? 1 : 0

  name         = "app-insights-connection-string"
  value        = module.application_insights[0].connection_string
  key_vault_id = module.key_vault[0].key_vault_id
  content_type = "terraform-managed"
}
