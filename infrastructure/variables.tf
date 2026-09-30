variable "product" {}

variable "component" {}

variable "location" {
  default = "UK South"
}

variable "env" {}

variable "subscription" {}

variable "common_tags" {
  type = map(string)
}

variable "aks_subscription_id" {
  type = string
}

variable "jenkins_AAD_objectId" {
  type = string
}

# Production uses this default. Only aat.tfvars selects a Burstable SKU.
variable "pgsql_sku" {
  type    = string
  default = "GP_Standard_D2s_v3"
}
