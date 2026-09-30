# dtsse-services

A small Spring Boot service for DTS Software Engineering. It has PostgreSQL connectivity, health endpoints, and a ListAssist Blob reader, with no application API, ingestion schedule, or schema yet.

## Local development

Use Java 25 and Docker Compose. Start PostgreSQL with `docker compose up -d postgres`, then run `./gradlew bootRun`. The local database, user, and password are all `services`. Check `http://localhost:4550/health/readiness`; it reports `UP` only when PostgreSQL is reachable. Run `./gradlew check` for the build checks. Stop the database with `docker compose down` (add `-v` to discard local data).

## CNP deployment

The Jenkins pipeline uses product `dtsse` and component `services`; the chart is under `charts/dtsse-services`. Preview and AAT use a non-persistent PostgreSQL 18 container in the release. The application is only ready after that database accepts connections. These databases are disposable; do not put data in them that must survive a restart or redeploy.

The smoke and functional tests call the deployed service at `TEST_URL`, which the pipeline sets after each AKS deployment; locally they default to `http://localhost:4550`.

The `hmcts/dtsse-services` repository is public, the `rse` GitHub team has write access, and the `jenkins-cft-d-i` topic is set. The [Jenkins deployment controls PR](https://github.com/hmcts/cnp-jenkins-config/pull/1355) must merge before Jenkins deploys anything, the repository must be added under `prod` in `environment-approvals.yml` or production Terraform is skipped, and a Flux HelmRelease is needed to run it in production. Follow the [CNP new component flow](https://hmcts.github.io/cloud-native-platform/new-component/) and validate preview or AAT first.

Production uses PostgreSQL Flexible Server 18, General Purpose `GP_Standard_D2s_v3`, with 32 GiB storage, no high availability replica, and seven days of backup retention, on the expanded PostgreSQL subnet. Terraform creates this database only for `prod` and writes its password to the existing `dtsse-prod` Key Vault as `services-POSTGRES-PASS`, which the chart mounts with `AppInsightsConnectionString` through the `dtsse` workload identity.

## ListAssist access

`ListAssistBlobReader` uses the Azure Blob SDK against `listassist.blob.endpoint` for the four core containers, whose physical names are set as `listassist.blob.containers.hearings`, `.sessions`, `.session-officers` and `.users`. None of these have defaults: deployments mount them from the `dtsse` Key Vault (`listassist-blob-endpoint` and `listassist-container-<key>`), and without an endpoint the application runs with no Blob access or ingestion. `LISTASSIST_BLOB_AUTH` is `entra` (default, `DefaultAzureCredential`) or `emulator`, which uses the public Azurite development key and refuses any endpoint that is not a plain HTTP `/devstoreaccount1` URL. There is no fallback between the two.

### Ingestion

A scheduled job (`LISTASSIST_INGEST_CRON`, default every 15 minutes, zone `LISTASSIST_INGEST_ZONE`, default Europe/London) runs only when `LISTASSIST_INGEST_ENABLED=true`, which defaults to false. Each run holds a PostgreSQL session advisory lock on its own connection, so only one replica ingests at a time. Each container is bootstrapped once from its latest Full extract; after that every unseen Blob version is ingested, including late arrivals. A download uses `If-Match` on the listed ETag, and each file's ledger row and selected rows commit in one transaction. Failed files are recorded in `listassist.source_file` and retried on later runs.

Flyway creates the `listassist` schema: the `source_file` ledger, `container_bootstrap`, and one table of selected source rows per container. Names, contact details and notes are never read. Views derive current state (`current_hearing_association`, `current_session`, `current_session_officer`, `session_officer_candidate`, `current_user_account`), with `observation_conflict` and `unusable_observation` for diagnostics. `ListAssistCandidateRepository.findCandidates(personalCode, date)` returns candidate hearings with diagnostics and per-container ingestion status. The results are evidence, not confirmed judicial assignments.

To run it locally against Azurite, start `docker compose up -d postgres azurite`, seed Azurite, then run `LISTASSIST_BLOB_AUTH=emulator LISTASSIST_BLOB_ENDPOINT=http://127.0.0.1:10000/devstoreaccount1 LISTASSIST_INGEST_ENABLED=true ./gradlew bootRun --args='--listassist.blob.containers.hearings=hearings --listassist.blob.containers.sessions=sessions --listassist.blob.containers.session-officers=session-officers --listassist.blob.containers.users=users'`, naming the containers you seeded. `./gradlew performance -Dlistassist.performance.scale=1.0` is a manual test, not part of `check`. It generates a large synthetic data set in a disposable PostgreSQL and prints the candidate query timings and plans.

### Azurite fixtures

`./gradlew integration` (also run by `./gradlew check`) needs Docker. It starts a fresh Azurite Blob emulator per test class through Testcontainers, pinned to `azurite:3.37.0` by digest, and generates synthetic Parquet for the four core containers, in a `baseline` scenario and a progressive `reassignment` scenario covering moves, cancellation and reinstatement, officer changes and a corrected overwrite. Column names and order come from `src/integrationTest/resources/listassist/parquet-schemas.json`. Files are written to `build/listassist-fixtures/<scenario>/` along with a `manifest.json` test oracle, which is never uploaded, and are seeded phase by phase using the public Azurite development key. The emulator client refuses any endpoint other than a plain HTTP emulator host with the `devstoreaccount1` path, and all fixture data is invented. For manual work, `docker compose up -d azurite` exposes the same image at `http://127.0.0.1:10000/devstoreaccount1`.

The chart dependency comes from the HMCTS private ACR. CI needs registry access to run `helm dependency build charts/dtsse-services`.
