# dtsse-services

A small Spring Boot service for DTS Software Engineering. It has PostgreSQL connectivity, health endpoints, and a ListAssist Blob reader, with no application API, ingestion schedule, or schema yet.

## Local development

Use Java 25 and Docker Compose. Start PostgreSQL with `docker compose up -d postgres`, then run `./gradlew bootRun`. The local database, user, and password are all `services`. Check `http://localhost:4550/health/readiness`; it reports `UP` only when PostgreSQL is reachable. Run `./gradlew check` for the build checks. Stop the database with `docker compose down` (add `-v` to discard local data).

## CNP deployment

The Jenkins pipeline uses product `dtsse` and component `services`; the chart is under `charts/dtsse-services`. Preview and AAT use a non-persistent PostgreSQL 18 container in the release. The application is only ready after that database accepts connections. These databases are disposable; do not put data in them that must survive a restart or redeploy.

The `hmcts/dtsse-services` repository is public, the `rse` GitHub team has write access, and the `jenkins-cft-d-i` topic is set. The [Jenkins deployment controls PR](https://github.com/hmcts/cnp-jenkins-config/pull/1355) must merge and a Flux HelmRelease is still needed before Jenkins can deploy it. Follow the [CNP new component flow](https://hmcts.github.io/cloud-native-platform/new-component/) and validate preview or AAT first.

Production uses PostgreSQL Flexible Server 18, General Purpose `GP_Standard_D2s_v3`, with 32 GiB storage, no high availability replica, and seven days of backup retention. Terraform creates this database only for `prod`. The chart expects its password in the existing `dtsse` Key Vault under `services-POSTGRES-PASS`. Provision and link that secret through the approved production process before deployment. The server's private network and JIT access requirements also need to be met. No production resources are created by this repository alone.

## ListAssist access

`ListAssistBlobReader` uses the Azure Blob SDK and `DefaultAzureCredential` against `https://mipersistentprod.blob.core.windows.net/`. It exposes internal list and download methods for `v3-sl-hearings`, `v3-sl-sessions`, `v3-sl-sessions-jofficer`, and `v3-sl-user`. Nothing invokes the reader yet.

The chart dependency comes from the HMCTS private ACR. CI needs registry access to run `helm dependency build charts/dtsse-services`.
