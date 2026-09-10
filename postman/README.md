# User and account API

The application exposes the following public development endpoints. Authentication
and transfers are not implemented. Responses use DTOs, never JPA entities.

| Method | Path | Success |
| --- | --- | --- |
| POST | `/api/users` | 201 Created |
| POST | `/api/users/{userId}/accounts` | 201 Created, with an account `Location` header |
| GET | `/api/accounts/{accountId}` | 200 OK |

## Start locally

Start Docker Desktop, then run from the project root:

```sh
cp -n .env.example .env
set -a
source .env
set +a
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
docker compose up -d --wait postgres
DEV_STARTING_BALANCE=1000.00 ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`DEV_STARTING_BALANCE` applies to the Spring `local` profile and defaults to `1000.00`.
The base configuration defaults to `0.00`. Negative values, more than two fractional
digits, or more than 17 integer digits fail configuration validation at startup.
The value applies only to accounts created afterward; it does not change existing
balances. Clients cannot choose the starting balance.

## Postman setup

Import [TPross.postman_collection.json](TPross.postman_collection.json),
or create the requests below manually. Set **Authorization: No Auth**. The collection
has `baseUrl`, `email`, `password`, `userId`, and `accountId` variables. Its response
scripts save the created IDs automatically. Send requests in the listed order.

`baseUrl` defaults to `http://localhost:8080`. Adjust it if using `SERVER_PORT=8081`.
Change `email` before creating another user; repeating an existing email returns 409.

## 1. Create a user

- Method: **POST**
- URL: `{{baseUrl}}/api/users`
- Header: `Content-Type: application/json`
- Body: **raw → JSON**

```json
{
  "email": "alice@example.com",
  "password": "development-password"
}
```

Example **201 Created** response (IDs and timestamps vary):

```json
{
  "id": 1,
  "email": "alice@example.com",
  "createdAt": "2026-10-01T12:00:00Z"
}
```

Email is trimmed and lowercased before validation/storage, must be nonblank, must
pass email validation, and has a maximum of 254 characters. An existing email is
checked without regard to case. The database unique constraint also protects
concurrent requests using the normalized email. Direct database writes still have
the model's case-sensitive uniqueness behavior.

Password must be nonblank and 8–128 characters long. The server uses Spring Security's
salted PBKDF2 encoder and stores an encoding identifier with the hash. Neither the
password nor its hash is returned. This adds password storage, not a login flow.
Unknown JSON fields such as `passwordHash`, `id`, or `createdAt` are rejected.

## 2. Create an account for that user

- Method: **POST**
- URL: `{{baseUrl}}/api/users/{{userId}}/accounts`
- Body: **none** (or raw JSON `{}` with `Content-Type: application/json`)

Use the ID returned by the first request. Example **201 Created** response with the
default local balance:

```json
{
  "id": 1,
  "userId": 1,
  "balance": 1000.00,
  "createdAt": "2026-10-01T12:01:00Z"
}
```

The response includes `Location: /api/accounts/1`. Accounts are initialized by the
service from validated server configuration. Sending `{"balance": 5000}` is rejected
with 400. One user may have multiple accounts; repeating a successful request creates
another account.

## 3. Retrieve the account

- Method: **GET**
- URL: `{{baseUrl}}/api/accounts/{{accountId}}`
- Body: **none**

Use the account ID from the second response. A successful response is **200 OK** with
the same account DTO structure shown above. It contains `userId`, not a nested user
entity, email, password, or hash.

## Errors

Central `@ControllerAdvice` returns `application/problem+json` with `type`, `title`,
`status`, `detail`, and `instance`. Field validation responses also contain an `errors`
map. Rejected values, passwords, SQL details, and stack traces are not included in
these error responses.

| Status | Example |
| --- | --- |
| 400 Bad Request | Invalid email/password, malformed or unsupported JSON fields, nonpositive/noninteger IDs |
| 404 Not Found | User does not exist when creating an account; account does not exist when retrieving |
| 409 Conflict | Duplicate email, including a database uniqueness race |
| 415 Unsupported Media Type | Sending user input as plain text instead of JSON |
| 500 Internal Server Error | Unexpected internal failure; response contains a generic message |

Example invalid-email response:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed.",
  "instance": "/api/users",
  "errors": {
    "email": "must be a well-formed email address"
  }
}
```

The security layer still denies unrelated routes. CSRF is bypassed for the two
public creation routes so they can be called without a session or token in Postman.
Login and ownership authorization are not implemented at this stage.

## Implementation and tests

- Controllers bind/validate DTOs and select HTTP status codes.
- `UserService` normalizes via the request DTO, checks duplicate email, encodes the
  password, and persists the user inside a transaction.
- `AccountService` verifies the owner, initializes the balance, and maps account
  responses inside transactions. Retrieval uses a read-only transaction, allowing
  lazy relationships to be accessed without relying on Open Session in View.
- Repositories handle persistence; the domain models are not exposed through HTTP.

```sh
# Web contracts, configuration validation, and existing domain tests
./mvnw verify

# Also test all application layers against disposable PostgreSQL
./mvnw verify -Ppostgres-integration
```

The integration suite uses its own PostgreSQL containers, not the Compose database.
It checks actual password encoding, normalized email uniqueness, configured balances,
DTO retrieval, missing resources, and rejection of client balance overrides.
