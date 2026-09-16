# TPross

Java 21 / Spring Boot 3.5.16 backend scaffold built with Maven. Dependencies include
Spring Web, Spring Data JPA, the PostgreSQL JDBC driver, Validation, Spring Security,
and JUnit 5 (through `spring-boot-starter-test`).

## Requirements

- JDK 21
- Docker with Docker Compose, or an existing PostgreSQL instance
- Internet access on the first build to download Maven and dependencies

The Maven Wrapper provides Maven 3.9.11; a separate Maven installation is optional.
On Windows, replace `./mvnw` with `mvnw.cmd`.

## Run locally

Run all commands from the repository root. Start Docker Desktop first.

### 1. Configure and start PostgreSQL

Create your local environment file once (keep an existing `.env` if you already have one):

```sh
cp -n .env.example .env
```

The example uses database `tpross`, username `tpross`, password `tpross`, and port
`5432`. These credentials are for local development only. Edit `.env` if needed.
It is ignored by Git; `.env.example` is tracked as the setup template.

Load the values into your shell, then start only PostgreSQL:

```sh
set -a
source .env
set +a
docker compose up -d --wait postgres
docker compose ps
```

The PostgreSQL service should show `healthy`. `docker-compose.yml` is the only
Compose file and contains only the database service; Spring Boot runs separately.

### 2. Start Spring Boot

In the same terminal, select Java 21 (macOS) and start the `local` profile:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

If you open a new terminal, run the `set -a` / `source .env` / `set +a` commands
again. Docker Compose automatically reads `.env`; Spring Boot does not. Exporting
the variables makes them available to the Java process.

### 3. Verify the database connection

In the Spring Boot startup output, look for messages containing:

```text
TProssPool - Added connection org.postgresql.jdbc.PgConnection@
TProssPool - Start completed.
Initialized JPA EntityManagerFactory for persistence unit 'default'
Started TProssApplication
```

The `Added connection` message confirms that the application's pool opened a real
PostgreSQL connection. The remaining messages confirm that JPA and application
startup completed. Exact prefixes, thread names, and connection IDs vary.

For independent confirmation while Spring Boot is still running, open another
terminal in the repository root and inspect its sessions inside PostgreSQL:

```sh
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT application_name, usename, datname, state
FROM pg_stat_activity
WHERE application_name = 'tpross';
SQL
```

Expect one or more rows with `application_name = tpross` and your configured
database/user. `idle` is normal: an established pooled connection is waiting for
work. The application sets the PostgreSQL connection property `ApplicationName`
to `tpross` so these sessions are identifiable. Run this check after successful
startup; an empty result means no matching application sessions currently exist.

Finally, verify the HTTP endpoint:

```sh
curl -i http://localhost:8080/api/health
```

Expected: HTTP 200 with `{"status":"UP"}`. This endpoint checks application
liveness only; it does not query PostgreSQL. Use the connection log/session checks
above to verify database connectivity.

### Stop locally

Stop Spring Boot with Ctrl+C. Stop PostgreSQL with `docker compose down`; its data
remains in the named Docker volume.

### Troubleshooting

- Docker socket/daemon error: start Docker Desktop and wait until it is ready.
- Connection refused: check `docker compose ps` and `docker compose logs postgres`.
- Missing `DB_URL`, `DB_USERNAME`, or `DB_PASSWORD`: export `.env` in the same shell
  that starts Spring Boot.
- Authentication failure: the `.env` credentials must match the database user.
  Compose initialization variables only apply when the data volume is first created;
  changing them later does not update existing users or passwords.
- Port 5432 already occupied: choose another `DB_PORT` in `.env` and update the port
  in `DB_URL` to match, then reload the environment and start Compose again.
- HTTP port 8080 already occupied: stop your previous Spring Boot process with
  Ctrl+C in its terminal, or start this app with `SERVER_PORT=8081` before the Maven
  command and use port 8081 when checking the health endpoint.

## Configuration

`src/main/resources/application.yml` reads these environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | Required | Full JDBC URL; example: `jdbc:postgresql://localhost:5432/tpross` |
| `DB_USERNAME` | Required | Database username; also used by Compose |
| `DB_PASSWORD` | Required | Database password; also used by Compose |
| `DB_PORT` | `5432` | Compose host port; keep consistent with `DB_URL` |
| `DB_NAME` | `tpross` | Compose database name; keep consistent with `DB_URL` |
| `SERVER_PORT` | `8080` | HTTP port |
| `DEV_STARTING_BALANCE` | `1000.00` | Starting balance for new accounts in the `local` profile; base configuration uses `0.00` |

When using an existing PostgreSQL instance, create its database/user first, set
the three required connection variables, and skip the Compose startup command.
The PostgreSQL driver and Hibernate dialect are inferred from the connection.

The base configuration keeps schema generation disabled (`ddl-auto: none`) and
Open Session in View disabled. The explicit `local` profile enables Hibernate's
`ddl-auto: update` and formatted SQL logging through `org.hibernate.SQL`. Hibernate
creates the `users`, `accounts`, and `transactions` tables locally from the entity
mappings. Use versioned migrations for shared environments; `update` is not a
replacement for migrations, especially when changing constraints on existing tables.
See [Spring Boot's Hibernate initialization documentation](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html).

The TPross entities, repositories, user/account APIs, and atomic transfers are implemented.
There are no schema migrations yet. Security permits the health
endpoint and the four API routes below. The creation routes are exempt from CSRF
checks for public development API access; other routes are denied. Authentication
is not implemented; form login and HTTP Basic are disabled.

## User, account, and transfer REST APIs

| Method | Path | Result |
| --- | --- | --- |
| POST | `/api/users` | 201, public user DTO; request contains email and password |
| POST | `/api/users/{userId}/accounts` | 201, account DTO and Location header; no body or `{}` |
| GET | `/api/accounts/{accountId}` | 200, account DTO |
| POST | `/api/transfers` | 201, completed transfer DTO |

Controllers call transactional services, which use repositories. Input DTOs are
validated and unknown fields are rejected. User creation normalizes email and stores
a salted PBKDF2 hash; passwords and hashes are never returned. Account responses
contain `id`, `userId`, `balance`, and `createdAt` instead of JPA entities.

The local profile initializes new accounts from `DEV_STARTING_BALANCE` (default
`1000.00`). Outside that profile, the configured default is `0.00`. Starting balances
must be nonnegative and fit `NUMERIC(19,2)`; invalid configuration fails startup.
The setting never rewrites existing balances. Clients cannot set balances.

Central `@ControllerAdvice` produces structured Problem Details responses: 400 for
invalid input, 404 for missing resources, and 409 for duplicate emails or data conflicts.
See [API usage and Postman examples](postman/README.md), or import the
[Postman collection](postman/TPross.postman_collection.json).

## Atomic peer-to-peer transfers

Send `POST /api/transfers` with `Content-Type: application/json`:

```json
{
  "sourceAccountId": 1,
  "destinationAccountId": 2,
  "amount": 25.50
}
```

Use IDs from existing accounts. Example `201 Created` response:

```json
{
  "transactionId": 1,
  "sourceAccountId": 1,
  "destinationAccountId": 2,
  "amount": 25.50,
  "status": "COMPLETED",
  "timestamp": "2026-10-01T12:00:00Z"
}
```

The DTO identifies the accounts by ID and does not expose JPA entities. The timestamp
is the transaction's `createdAt` instant. Account IDs must be positive integers;
fractional IDs are rejected instead of truncated. Amounts use `BigDecimal`, must be
at least `0.01`, and may have at most two fractional digits and 17 integer digits.
The accounts must differ, both must exist, and the source must have sufficient funds.
Spending the exact balance is allowed. Transfers between two accounts of the same user
are also allowed. A destination balance exceeding `NUMERIC(19,2)` is rejected.

`TransferService.createTransfer` uses Spring's
`@Transactional(rollbackFor = Exception.class)`. Account lookups, the debit, the
credit, and the `COMPLETED` Transaction insert share one database transaction.
JPA dirty checking persists the managed account changes; `saveAndFlush` writes the
transaction and pending balance changes, but **flush is not commit**. Spring commits
after the service method succeeds, before the controller sends its success response.

If an exception escapes during this operation, Spring rolls back the entire unit of
work: neither balance change nor the transaction record remains committed, even if
SQL has already executed. No `FAILED` record is saved separately. Runtime exceptions
and errors trigger rollback by default; `rollbackFor = Exception.class` explicitly
includes checked exceptions too. The service does not catch and suppress failures;
the controller advice translates them into HTTP errors after rollback. A failure after
a successful commit, such as losing the HTTP response, cannot undo that commit.
See [Spring's rollback rules](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html).

Atomicity alone does not prevent concurrent requests from reading the same old balance.
The repository uses `PESSIMISTIC_WRITE` to lock both rows **in ascending account-ID
order** before checking funds. Locks last until commit or rollback. Competing transfers
wait, then use the current balances; a consistent order avoids opposite-direction
transfers acquiring these locks in conflicting orders. Future balance-writing code
must follow the same locking protocol. See [PostgreSQL row locks](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS).

Errors: `400` for invalid input or the same account, `404` for a missing account,
`409` for insufficient funds, destination overflow, or a database locking conflict,
and `500` for unexpected failures. All use the existing Problem Details handler.
This remains a public development API without authentication or ownership authorization.
Idempotency is not implemented: repeating a successful POST creates another transfer;
do not blindly retry an ambiguous response or connection failure.

## TPross domain model

TPross domain models are in `com.tpross.model`.

| Entity | Fields and defaults |
| --- | --- |
| `User` | Generated `Long` ID, email, password hash, creation timestamp |
| `Account` | Generated `Long` ID, owning user, `BigDecimal` balance (entity default `0.00`; API uses configured starting balance), creation timestamp |
| `Transaction` | Generated `Long` ID, source and destination accounts, `BigDecimal` amount, status (initially `PENDING`), creation timestamp |

- `Account.user` is a required, lazy `@ManyToOne`: one user may own multiple accounts.
- Both transaction account references are required, lazy `@ManyToOne`: each account
  may participate in multiple incoming and outgoing transactions. Transfers between
  different accounts belonging to the same user are allowed.
- Relationships are unidirectional. No parent-side collections or cascading deletes
  are added. Foreign keys reject deletion of a user with accounts or an account
  referenced by transactions. Foreign-key columns are indexed for future lookups.
- Tables use plural names to avoid collisions with SQL identifiers such as `user`.
- All requested fields are non-null in the database. Email has a unique constraint
  and a 254-character limit; Bean Validation checks its format and rejects blanks.
  Database uniqueness uses exact, case-sensitive values. The REST API additionally
  trims/lowercases emails and checks for duplicates without regard to case. Password
  hashes are nonblank, at most 255 characters, and excluded from Jackson JSON
  serialization. The user service generates hashes; login is not implemented.
- Money is stored as PostgreSQL `NUMERIC(19,2)` (17 integer digits and two fractional
  digits). Bean Validation rejects extra fractional digits or excessive precision
  before ORM persistence. This initial model assumes a single currency with two decimal
  places; no currency code or conversion is modeled.
- Balances must be nonnegative (no overdrafts); amounts must be positive (minimum
  `0.01`). Database checks enforce these rules and reject identical source/destination
  account IDs, including writes made outside JPA. PostgreSQL itself can round extra
  fractional digits supplied through direct SQL; the precision rejection is at the
  Bean Validation layer.
- `TransactionStatus` is stored by name (`PENDING`, `COMPLETED`, `FAILED`), so enum
  reordering does not change stored meanings. Status transition rules are not implemented.
- `createdAt` uses `Instant`, is assigned by `@PrePersist`, and is not updatable through
  JPA. Account ownership and transaction account/amount fields are also not updatable
  through JPA; these are mapping restrictions, not database triggers.

`UserRepository`, `AccountRepository`, and `TransactionRepository` extend
`JpaRepository<Entity, Long>` to provide standard persistence operations. Saving a
transaction directly does **not** debit/credit accounts; `TransferService` coordinates
those balance changes atomically with row locks. Idempotency and authentication are
not implemented.

## Build and test

```sh
./mvnw clean verify
java -jar target/tpross-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

The default tests cover health, user/account, and transfer HTTP contracts, security filters,
configuration validation, monetary/input validation, and password-hash serialization.
They do not require a database.
Running the packaged application requires PostgreSQL and the exported variables above.

To also run the persistence integration tests with Docker running:

```sh
./mvnw verify -Ppostgres-integration
```

Testcontainers starts a disposable PostgreSQL 17 container and supplies its connection
settings; `.env` and the Compose database are not used. Failsafe runs `DomainPersistenceIT`,
`UserAccountApiIT`, and `TransferApiIT` to verify persistence constraints and API flows.
Transfer tests verify rejection without writes, rollback after real SQL flushes,
simultaneous debits/credits, and opposite-direction transfers under actual lock contention.
Test tables are created/dropped only inside disposable
databases. The integration profile requires Docker and fails if it is unavailable.

## Files and packages

All Java packages are under `com.tpross`.

| File | Purpose |
| --- | --- |
| `pom.xml` | Java 21 target, Spring Boot dependency management, requested dependencies, and executable JAR plugin |
| `mvnw` | Maven Wrapper launcher for macOS/Linux |
| `mvnw.cmd` | Maven Wrapper launcher for Windows |
| `.mvn/wrapper/maven-wrapper.properties` | Wrapper version and pinned Maven distribution |
| `.gitignore` | Ignores build output, local IDE files, logs, and environment files |
| `.env.example` | Copyable local environment template without real credentials |
| `docker-compose.yml` | PostgreSQL-only service, readiness check, and persistent data volume |
| `src/main/resources/application.yml` | Application name, database/JPA settings, and HTTP port |
| `src/main/resources/application-local.yml` | Local-only Hibernate schema updates and SQL logging |
| `src/main/java/com/tpross/TProssApplication.java` | Spring Boot application entry point |
| `src/main/java/com/tpross/controller/HealthController.java` | Public `GET /api/health` endpoint |
| `src/main/java/com/tpross/dto/HealthResponse.java` | Immutable JSON response with a `status` field |
| `src/main/java/com/tpross/security/SecurityConfig.java` | Initial route access policy |
| `src/main/java/com/tpross/controller/{User,Account}Controller.java` | User/account REST endpoints |
| `src/main/java/com/tpross/controller/TransferController.java` | Public transfer creation endpoint |
| `src/main/java/com/tpross/dto/{CreateTransferRequest,TransferResponse}.java` | Validated transfer input and public result |
| `src/main/java/com/tpross/dto/{CreateUserRequest,CreateAccountRequest,UserResponse,AccountResponse}.java` | Validated input and public output contracts |
| `src/main/java/com/tpross/service/{User,Account}Service.java` | Transactional user/account operations |
| `src/main/java/com/tpross/service/TransferService.java` | Atomic debit, credit, and transaction recording with ordered row locks |
| `src/main/java/com/tpross/service/package-info.java` | Service package documentation |
| `src/main/java/com/tpross/repository/package-info.java` | Repository package documentation |
| `src/main/java/com/tpross/repository/{User,Account,Transaction}Repository.java` | Spring Data JPA repositories for each entity |
| `src/main/java/com/tpross/model/package-info.java` | Model package documentation |
| `src/main/java/com/tpross/model/{User,Account,Transaction}.java` | Initial TPross persistence entities |
| `src/main/java/com/tpross/model/TransactionStatus.java` | Named transaction statuses |
| `src/main/java/com/tpross/exception/{ApiExceptionHandler,ResourceNotFoundException,DuplicateEmailException}.java` | Centralized API errors |
| `src/main/java/com/tpross/exception/{InvalidTransferException,TransferConflictException}.java` | Same-account and transfer balance conflicts |
| `src/main/java/com/tpross/exception/package-info.java` | Exception package documentation |
| `src/main/java/com/tpross/config/{ApplicationConfig,AccountProperties}.java` | Password encoder and validated account settings |
| `src/main/java/com/tpross/config/package-info.java` | Configuration package documentation |
| `src/test/java/com/tpross/controller/HealthControllerTest.java` | Health endpoint and route access tests |
| `src/test/java/com/tpross/model/DomainValidationTest.java` | Validation and password-hash serialization tests |
| `src/test/java/com/tpross/repository/DomainPersistenceIT.java` | PostgreSQL persistence/constraint integration tests |
| `src/test/java/com/tpross/controller/UserAccountControllerTest.java` | User/account HTTP contract and error tests |
| `src/test/java/com/tpross/controller/UserAccountApiIT.java` | Full API flow against disposable PostgreSQL |
| `src/test/java/com/tpross/controller/TransferControllerTest.java` | Transfer HTTP validation and error contracts |
| `src/test/java/com/tpross/controller/TransferApiIT.java` | PostgreSQL transfer, rollback, and concurrency tests |
| `src/test/java/com/tpross/config/AccountPropertiesTest.java` | Starting-balance configuration validation |
| `postman/README.md` | Endpoint documentation and Postman examples |
| `postman/TPross.postman_collection.json` | Importable Postman requests with automatic ID capture |
| `README.md` | Setup, configuration, run commands, and file reference (updated) |
