# Infrastructure

In `aat` and `prod`, Terraform creates the product's infrastructure, so a deployment in AAT uses the same wiring as production. Preview and the pipeline's AAT staging deploy use the PostgreSQL container declared in their Helm values instead.

The `dtsse-services-<env>` resource group holds the `dtsse-services-<env>` Key Vault and the `dtsse-services-appinsights` Application Insights, whose connection string is written to the vault as `app-insights-connection-string`. The Key Vault module also creates the `dtsse-services-<env>-mi` managed identity, which the namespace's workload identity uses, with read access to the vault. The DTS CFT Software Engineering group can manage the vault's secrets.

The PostgreSQL Flexible Server `dtsse-services-api-<env>` holds the `services` database. It uses `hmcts/terraform-module-postgresql-flexible` at `master`, the ref allowed by the CNP Terraform approvals, with PostgreSQL 18, 32 GiB storage, no high availability replica, seven days of backups and the `postgres-expanded` subnet. Production uses `GP_Standard_D2s_v3`, the `pgsql_sku` default; only `aat.tfvars` selects the Burstable `B_Standard_B1ms`. The admin password is written to the vault as `api-POSTGRES-PASS`, where the chart reads it. The `azurerm.postgres_network` provider points to the AKS subscription for private networking. `aks_subscription_id` and `jenkins_AAD_objectId` come from the CNP Jenkins pipeline.

The module enables reader group access by default: `DTS CFT DB Access Reader` in AAT, and `DTS JIT Access dtsse-services DB Reader SC` in production, which must exist before the production Terraform run, following the [CNP database guidance](https://hmcts.github.io/cloud-native-platform/infrastructure/database/). Writer JIT access is not enabled here.
