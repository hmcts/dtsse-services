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

  source = "git@github.com:hmcts/terraform-module-postgresql-flexible?ref=dc65f69d0f7afdea16a7ea904ffeadfc0184b658"

  providers = {
    azurerm.postgres_network = azurerm.postgres_network
  }

  env                  = var.env
  product              = var.product
  component            = var.component
  business_area        = "cft"
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
