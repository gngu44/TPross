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

From the repository root, select Java 21. On macOS:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

Start the local database and application:

```sh
docker compose up -d --wait
./mvnw spring-boot:run
```

In another terminal:

```sh
curl -i http://localhost:8080/api/health
```

Expected response: HTTP 200 with `{"status":"UP"}`. This is a simple application
liveness endpoint; it does not query the database or report database readiness.
JPA initialization still requires an accessible PostgreSQL database at startup.

Stop the application with Ctrl+C. Stop the local database with
`docker compose down`; its data remains in the named Docker volume.

## Configuration

`src/main/resources/application.yml` reads these environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/tpross` | Full JDBC URL; overrides the URL assembled from `DB_PORT` and `DB_NAME` |
| `DB_PORT` | `5432` | Local database port |
| `DB_NAME` | `tpross` | Database name |
| `DB_USERNAME` | `tpross` | Database username |
| `DB_PASSWORD` | `tpross` | Database password |
| `SERVER_PORT` | `8080` | HTTP port |

The default database credentials are for local development only. Export environment
variables in the shell before running the application and Compose to override them.
Spring Boot does not automatically load `.env` files. When using an existing database,
create the database/user first, configure the variables, and skip the Compose command.
Compose initialization variables apply when its database volume is first created.

Schema generation is disabled (`ddl-auto: none`), and Open Session in View is disabled.
There are no entities, schema migrations, or business features yet. Security permits
only `GET /api/health`, denies other routes with HTTP 403, and leaves CSRF protection
enabled. Authentication is not implemented; form login and HTTP Basic are disabled.

## Build and test

```sh
./mvnw clean verify
java -jar target/tpross-0.0.1-SNAPSHOT.jar
```

Tests exercise the health response and security filters using JUnit 5 and MockMvc.
They do not require a database. Running the packaged application requires PostgreSQL.

## Files and packages

All Java packages are under `com.tpross`.

| File | Purpose |
| --- | --- |
| `pom.xml` | Java 21 target, Spring Boot dependency management, requested dependencies, and executable JAR plugin |
| `mvnw` | Maven Wrapper launcher for macOS/Linux |
| `mvnw.cmd` | Maven Wrapper launcher for Windows |
| `.mvn/wrapper/maven-wrapper.properties` | Wrapper version and pinned Maven distribution |
| `.gitignore` | Ignores build output, local IDE files, logs, and environment files |
| `compose.yaml` | Local PostgreSQL service, readiness check, and persistent data volume |
| `src/main/resources/application.yml` | Application name, database/JPA settings, and HTTP port |
| `src/main/java/com/tpross/TProssApplication.java` | Spring Boot application entry point |
| `src/main/java/com/tpross/controller/HealthController.java` | Public `GET /api/health` endpoint |
| `src/main/java/com/tpross/dto/HealthResponse.java` | Immutable JSON response with a `status` field |
| `src/main/java/com/tpross/security/SecurityConfig.java` | Initial route access policy |
| `src/main/java/com/tpross/service/package-info.java` | Placeholder/documentation for future business services |
| `src/main/java/com/tpross/repository/package-info.java` | Placeholder/documentation for future persistence repositories |
| `src/main/java/com/tpross/model/package-info.java` | Placeholder/documentation for future domain models and entities |
| `src/main/java/com/tpross/exception/package-info.java` | Placeholder/documentation for future exceptions and handlers |
| `src/main/java/com/tpross/config/package-info.java` | Placeholder/documentation for future general configuration |
| `src/test/java/com/tpross/controller/HealthControllerTest.java` | Health endpoint and route access tests |
| `README.md` | Setup, configuration, run commands, and file reference (updated) |
