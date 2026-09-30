data "azurerm_user_assigned_identity" "jenkins" {
  count = local.create_infrastructure ? 1 : 0

  name                = "jenkins-${var.env}-mi"
  resource_group_name = "managed-identities-${var.env}-rg"
}

resource "azurerm_resource_group" "rg" {
  count = local.create_infrastructure ? 1 : 0

  name     = "${var.product}-${var.env}"
  location = var.location
  tags     = var.common_tags
}

# Also creates the ${product}-${env}-mi managed identity used by the namespace's workload identity, with read
# access to the vault.
module "key_vault" {
  count = local.create_infrastructure ? 1 : 0

  source                  = "git@github.com:hmcts/cnp-module-key-vault?ref=master"
  product                 = var.product
  env                     = var.env
  object_id               = var.jenkins_AAD_objectId
  resource_group_name     = azurerm_resource_group.rg[0].name
  jenkins_object_id       = data.azurerm_user_assigned_identity.jenkins[0].principal_id
  product_group_name      = "DTS CFT Software Engineering"
  common_tags             = var.common_tags
  create_managed_identity = true
}
