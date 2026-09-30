# Infrastructure

Terraform creates a PostgreSQL Flexible Server and the `services` database in `aat` and `prod`, so a deployment in AAT uses the same wiring as production. Preview and the pipeline's AAT staging deploy use the PostgreSQL container declared in their Helm values. Production uses `GP_Standard_D2s_v3`, the `pgsql_sku` default; only `aat.tfvars` selects the Burstable `B_Standard_B1ms`.

The module uses `hmcts/terraform-module-postgresql-flexible` at `master`, the ref allowed by the CNP Terraform approvals, and selects PostgreSQL 18, 32 GiB storage, no high availability replica, seven days of backups and the `postgres-expanded` subnet. The `azurerm.postgres_network` provider points to the AKS subscription for private networking. `aks_subscription_id` and `jenkins_AAD_objectId` come from the CNP Jenkins pipeline.

The module's generated admin password is written to the environment's existing `dtsse` Key Vault (`dtsse-aat` or `dtsse-prod`) as `services-POSTGRES-PASS`, where the chart reads it. The chart's database host assumes the module's standard `dtsse-services-<env>` server name.

The module enables reader group access by default: `DTS CFT DB Access Reader` in AAT, and `DTS JIT Access dtsse DB Reader SC`, which must exist before the production Terraform run, following the [CNP database guidance](https://hmcts.github.io/cloud-native-platform/infrastructure/database/). Writer JIT access is not enabled here.

In `prod`, Terraform also creates the service's own managed identity, `dtsse-services-prod-mi`, in `managed-identities-prod-rg`. The pod runs as a `dtsse-services` service account that only this identity trusts, so storage access granted to it isn't available to other apps in the `dtsse` namespace. Its client and principal IDs are the `workload_identity_client_id` and `workload_identity_principal_id` outputs, for the Flux service account and the storage grant.
