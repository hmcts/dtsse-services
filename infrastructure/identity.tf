# The service's own workload identity, separate from the dtsse namespace's dtsse-<env>-mi, so storage access granted
# to it is only usable by pods running as the dtsse-services service account. Flux references it and adds the
# federated credential for that service account.
resource "azurerm_user_assigned_identity" "dtsse_services" {
  count = local.create_infrastructure ? 1 : 0

  name                = "${var.product}-${var.component}-${var.env}-mi"
  resource_group_name = "managed-identities-${var.env}-rg"
  location            = var.location
  tags                = var.common_tags

  # Its client and principal IDs are copied into cnp-flux-config and the storage grant, so a replacement would
  # silently break authentication. Remove this deliberately, then update both, if it ever has to change.
  lifecycle {
    prevent_destroy = true
  }
}
