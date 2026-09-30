# Infrastructure

Terraform creates one PostgreSQL Flexible Server and the `services` database in `prod` only. Preview, AAT and other non-production releases use the PostgreSQL container declared in their Helm values. No Burstable SKU is used.

The production module uses `hmcts/terraform-module-postgresql-flexible` at `master`, the ref allowed by the CNP Terraform approvals, and selects PostgreSQL 18, `GP_Standard_D2s_v3`, 32 GiB storage, no high availability replica, seven days of backups and the `postgres-expanded` subnet. The `azurerm.postgres_network` provider points to the AKS subscription for private networking. `aks_subscription_id` and `jenkins_AAD_objectId` come from the CNP Jenkins pipeline.

The module's generated admin password is written to the existing `dtsse-prod` Key Vault as `services-POSTGRES-PASS`, where the chart reads it. The chart's production hostname assumes the module's standard `dtsse-services-prod` server name.

The module enables reader group access by default. `DTS JIT Access dtsse DB Reader SC` must exist before the production Terraform run, following the [CNP database guidance](https://hmcts.github.io/cloud-native-platform/infrastructure/database/). Writer JIT access is not enabled here.
