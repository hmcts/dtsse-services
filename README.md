# dtsse-services

A small Spring Boot service for DTS Software Engineering. It has PostgreSQL connectivity, health endpoints, and a ListAssist Blob reader, with no application API yet.

## Local development

Use Java 25 and Docker Compose. Start PostgreSQL with `docker compose up -d postgres`, then run `./gradlew bootRun`. The local database, user, and password are all `services`. Check `http://localhost:4550/health/readiness`; it reports `UP` only when PostgreSQL is reachable. Run `./gradlew check` for the build checks. Stop the database with `docker compose down` (add `-v` to discard local data).

## CNP deployment

The Jenkins pipeline uses product `dtsse` and component `services`; the chart is under `charts/dtsse-services`. Preview and AAT use a non-persistent PostgreSQL 18 container in the release. The application is only ready after that database accepts connections. These databases are disposable; do not put data in them that must survive a restart or redeploy.

The smoke and functional tests call the deployed service at `TEST_URL`, which the pipeline sets after each AKS deployment; locally they default to `http://localhost:4550`.

### Ingestion

A scheduled job (`LISTASSIST_INGEST_CRON`, default every 15 minutes, zone `LISTASSIST_INGEST_ZONE`, default Europe/London) runs only when `LISTASSIST_INGEST_ENABLED=true`, which defaults to false. Each run holds a PostgreSQL session advisory lock on its own connection, so only one replica ingests at a time. Each container is bootstrapped once from its latest Full extract; after that every unseen Blob version is ingested, including late arrivals. A download uses `If-Match` on the listed ETag, and each file's ledger row and selected rows commit in one transaction. Failed files are recorded in `listassist.source_file` and retried on later runs.

To run it locally against Azurite, start `docker compose up -d postgres azurite`, seed Azurite, then run `LISTASSIST_BLOB_AUTH=emulator LISTASSIST_BLOB_ENDPOINT=http://127.0.0.1:10000/devstoreaccount1 LISTASSIST_INGEST_ENABLED=true ./gradlew bootRun --args='--listassist.blob.containers.hearings=hearings --listassist.blob.containers.sessions=sessions --listassist.blob.containers.session-officers=session-officers --listassist.blob.containers.users=users'`, naming the containers you seeded. `./gradlew performance -Dlistassist.performance.scale=1.0` is a manual test, not part of `check`. It generates a large synthetic data set in a disposable PostgreSQL and prints the candidate query timings and plans.
