locals {
  # AAT has its own database so a deployment there uses the same wiring as production.
  create_database = contains(["aat", "prod"], var.env)
}

provider "azurerm" {
  features {}
}

provider "azurerm" {
  alias                           = "postgres_network"
  subscription_id                 = var.aks_subscription_id
  resource_provider_registrations = "none"
  features {}
}

module "postgresql" {
  count = local.create_database ? 1 : 0

  source = "git@github.com:hmcts/terraform-module-postgresql-flexible?ref=master"

  providers = {
    azurerm.postgres_network = azurerm.postgres_network
  }

  env                  = var.env
  product              = var.product
  component            = var.component
  business_area        = "cft"
  subnet_suffix        = "expanded"
  common_tags          = var.common_tags
  admin_user_object_id = var.jenkins_AAD_objectId

  pgsql_version         = "18"
  pgsql_sku             = var.pgsql_sku
  pgsql_storage_mb      = 32768
  high_availability     = false
  backup_retention_days = 7
  pgsql_databases = [
    {
      name = "services"
    }
  ]
}

data "azurerm_key_vault" "dtsse" {
  count = local.create_database ? 1 : 0

  name                = "${var.product}-${var.env}"
  resource_group_name = "${var.product}-${var.env}"
}

resource "azurerm_key_vault_secret" "postgres_password" {
  count = local.create_database ? 1 : 0

  name         = "${var.component}-POSTGRES-PASS"
  value        = module.postgresql[0].password
  key_vault_id = data.azurerm_key_vault.dtsse[0].id
}
