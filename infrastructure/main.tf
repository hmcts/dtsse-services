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
  count = var.env == "prod" ? 1 : 0

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
  pgsql_sku             = "GP_Standard_D2s_v3"
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
  count = var.env == "prod" ? 1 : 0

  name                = "${var.product}-${var.env}"
  resource_group_name = "${var.product}-${var.env}"
}

resource "azurerm_key_vault_secret" "postgres_password" {
  count = var.env == "prod" ? 1 : 0

  name         = "${var.component}-POSTGRES-PASS"
  value        = module.postgresql[0].password
  key_vault_id = data.azurerm_key_vault.dtsse[0].id
}
