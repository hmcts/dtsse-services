# Infrastructure

Terraform creates one PostgreSQL Flexible Server and the `services` database in `prod` only. Preview, AAT and other non-production releases use the PostgreSQL container declared in their Helm values. No Burstable SKU is used.

The production module is pinned to a commit of `hmcts/terraform-module-postgresql-flexible` and selects PostgreSQL 18, `GP_Standard_D2s_v3`, 32 GiB storage, no high availability replica, and seven days of backups. The `azurerm.postgres_network` provider points to the AKS subscription for private networking. Supply `aks_subscription_id` and `jenkins_AAD_objectId` through the CNP Jenkins pipeline variables.

Before a production deployment, use the approved process to put the module's sensitive `postgresql_password` output in the existing `dtsse` Key Vault as `services-POSTGRES-PASS`. Confirm the team's database JIT access and private network setup with CNP. The chart's production hostname assumes the module's standard `dtsse-services-prod` server name.

The module enables reader group access by default. Create `DTS JIT Access dtsse DB Reader SC` and its access package before the production Terraform run, following the [CNP database guidance](https://hmcts.github.io/cloud-native-platform/infrastructure/database/). Writer JIT access is not enabled here.
