# Production Flyway baseline runbook

This is a one-time, operator-run procedure for the existing PostgreSQL database. It is not an application startup action and it must not be run against an unreviewed target.

## Scope

The approved baseline version is `1`. The matching source-controlled clean-database baseline is `B1__current_application_schema.sql` under `db/migration/postgresql`.

Running `flyway baseline` against the accepted existing schema creates Flyway history metadata only. It does **not** execute B1, alter application tables, insert application data, or modify the intentionally preserved `public.carts` row.

## Required preconditions

1. Use Flyway CLI **12.4.0**, matching Spring Boot 4.1.0 dependency management. Run `flyway -v` and record the result before any `baseline` or `info` command.
2. Use explicit host, port, username, and database. The `psql` verification command uses its own interactive `--password` prompt. Flyway does not prompt for a missing password; use the process-scoped `FLYWAY_PASSWORD` procedure below. Do not place a password in command arguments, command history, output, this document, or source control.
3. Verify with read-only SQL that `current_database()` is exactly `sushidb`, `current_schema()` is `public`, and review `SHOW search_path`.
4. Verify and record the PostgreSQL server version.
5. Confirm `to_regclass('public.flyway_schema_history')` is null.
6. Compare a fresh schema-only fingerprint with the reviewed, approved schema fingerprint.
7. Confirm a restorable backup has completed and that the approved aggregate data-profile output has been reviewed.
8. Confirm that `public.carts` is an intentionally retained legacy table and its existing row must not be changed, deleted, or mapped.
9. Confirm that the proposed release contains the reviewed B1 artifact and no migration above version 1 is being deployed at the same time.

Run this read-only verification with explicit connection values and an interactive password prompt:

~~~text
psql --host="<host>" --port="<port>" --username="<username>" --dbname="sushidb" --password --no-psqlrc --set=ON_ERROR_STOP=1 --command="SELECT current_database(), current_schema(); SHOW search_path; SHOW server_version; SELECT to_regclass('public.flyway_schema_history');"
~~~

It must return sushidb, public, the reviewed search path and server version, and a null history-table registration before baselining.

## Manual baseline and post-baseline verification

From an approved Flyway 12.4.0 CLI distribution and the reviewed release migration directory, run this PowerShell procedure in an operator-controlled session. Replace angle-bracketed values only in the active shell. The password is passed to Flyway only through a process-scoped environment variable, removed in `finally`, and the temporary unmanaged BSTR buffer is zeroed and freed.

```powershell
$securePassword = Read-Host -AsSecureString -Prompt 'Flyway database password'
$unmanagedPassword = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)

try {
    $env:FLYWAY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($unmanagedPassword)
    $flywayArguments = @(
        '-url=jdbc:postgresql://<host>:<port>/sushidb',
        '-user=<username>',
        '-locations=filesystem:<reviewed-release>/db/migration/postgresql',
        '-defaultSchema=public',
        '-schemas=public',
        '-baselineOnMigrate=false',
        '-baselineVersion=1',
        '-cleanDisabled=true'
    )

    & flyway -v
    if ($LASTEXITCODE -ne 0) { throw 'Flyway version check failed.' }

    & flyway @flywayArguments baseline
    if ($LASTEXITCODE -ne 0) { throw 'Flyway baseline failed.' }

    & flyway -v
    if ($LASTEXITCODE -ne 0) { throw 'Flyway version check failed.' }

    & flyway @flywayArguments info
    if ($LASTEXITCODE -ne 0) { throw 'Flyway info failed.' }
}
finally {
    Remove-Item Env:FLYWAY_PASSWORD -ErrorAction SilentlyContinue
    if ($null -ne $unmanagedPassword -and $unmanagedPassword -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($unmanagedPassword)
    }
    $securePassword.Dispose()
}
```

Do not use `baselineOnMigrate=true`. Do not run `migrate` before the history row is verified. Do not use `clean`, `repair`, or an application deployment as a substitute for this procedure.

Verify that `public.flyway_schema_history` contains the version-1 `BASELINE` entry and that no B1 `SQL_BASELINE` execution occurred. Do not deploy the application until this is confirmed and recorded.

## Ownership boundaries

- Flyway owns `public.cart`, `public.cart_items`, `public.orders`, `public.conversation_sessions`, and legacy `public.carts`.
- Infrastructure owns the PostgreSQL `vector` extension.
- LangChain4j owns `menu_embeddings`.

B1 intentionally does not create, alter, index, validate, or drop the extension or `menu_embeddings`.
