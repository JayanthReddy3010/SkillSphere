# SkillSphere — Neon Final Verified Migration Execution Guide

**Document Version:** 2.0.0
**Project:** SkillSphere (Infosys Springboard Virtual Internship 7.0)
**Target Environment:** Neon Serverless PostgreSQL (Production Free Tier)
**Execution Context:** Manual Human Operator Execution via Windows PowerShell 7 / Windows PowerShell 5.1
**Safety Status:** PREPARATION & DOCUMENTATION ONLY (No remote connections initiated during audit)

---

## 1. Executive Summary & Architectural Overview

This guide provides a secure, deterministic, PowerShell-only procedure for executing Flyway migrations (V1 through V12) against a newly provisioned Neon Serverless PostgreSQL database.

### Strict Safety & Security Principles
1. **Zero Hardcoded Secrets**: Real passwords, tokens, and complete connection strings are never stored in source code, scripts, or documentation.
2. **Hidden Credential Entry**: Credentials are captured interactively using PowerShell `Read-Host -AsSecureString`. Console input is completely hidden (no echoing, no plaintext).
3. **Child-Process Isolation**: Credentials are passed strictly via process-scoped environment variables to child processes (`psql`, `mvn`) and are cleared immediately upon completion.
4. **Direct Connection Enforcement**: Flyway migrations must target Neon's **direct connection endpoint (port 5432)**, never the pooled PgBouncer endpoint (port 6543), because Flyway uses session locks and transactional DDL.
5. **Multi-Stage Checkpoints**: Execution is structured into discrete, explicit checkpoints. The post-migration verification suite runs independently after the Spring Boot runner is stopped.

---

## 2. Neon Connection Parameters (From Neon Console)

Before running this procedure, retrieve these parameters from your Neon Project Dashboard under **Connection Details**:

| Parameter Name | Example Value | Description |
| :--- | :--- | :--- |
| **`NEON_HOST`** | `ep-cool-fog-123456.us-east-2.aws.neon.tech` | Target Neon compute endpoint (direct, not pooled) |
| **`NEON_PORT`** | `5432` | Standard PostgreSQL direct port (DO NOT use 6543 pooler) |
| **`NEON_DB`** | `neondb` (or `skillsphere_prod`) | Production target database name |
| **`NEON_USER`** | `neondb_owner` (or assigned user) | Neon database owner username |
| **`ADMIN_EMAIL`** | `admin@skillsphere.internal` | Production administrator login email |
| **`ADMIN_FULL_NAME`** | `System Administrator` | Full name for the bootstrap administrator |

---

## 3. Step-by-Step Multi-Stage Execution Procedure

### Checkpoint 1: Session Environment & Client Tool Verification

Open an elevated or standard Windows PowerShell console and navigate to the backend application directory.

```powershell
# 1. Navigate to backend application directory
Set-Location -Path "C:\Users\gjaya\OneDrive\Desktop\SkillSphere\skillsphere\backend"

# 2. Verify psql.exe availability (check PATH, then standard PostgreSQL 18 installation path)
$psqlPath = Get-Command psql.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $psqlPath) {
    if (Test-Path "D:\PostgreSQL\18\bin\psql.exe") {
        $psqlPath = "D:\PostgreSQL\18\bin\psql.exe"
        $env:Path = "D:\PostgreSQL\18\bin;" + $env:Path
        Write-Host "[OK] Located psql.exe at: $psqlPath" -ForegroundColor Green
    } elseif (Test-Path "C:\Program Files\PostgreSQL\18\bin\psql.exe") {
        $psqlPath = "C:\Program Files\PostgreSQL\18\bin\psql.exe"
        $env:Path = "C:\Program Files\PostgreSQL\18\bin;" + $env:Path
        Write-Host "[OK] Located psql.exe at: $psqlPath" -ForegroundColor Green
    } else {
        Write-Error "[FATAL] psql.exe not found in PATH or standard install directories. Aborting."
        return
    }
} else {
    Write-Host "[OK] Using psql.exe from system PATH: $psqlPath" -ForegroundColor Green
}

# 3. Configure target connection parameters (substitute with authentic Neon dashboard values)
$env:PGHOST = "ep-your-endpoint-id.us-east-2.aws.neon.tech"
$env:PGPORT = "5432"
$env:PGDATABASE = "neondb"
$env:PGUSER = "neondb_owner"

# Enforce strict SSL/TLS encryption for all client connections
$env:PGSSLMODE = "require"
$env:DB_URL = "jdbc:postgresql://${env:PGHOST}:${env:PGPORT}/${env:PGDATABASE}?sslmode=require"
$env:DB_USERNAME = $env:PGUSER

# Bind Spring Boot migration runner to loopback interface and unused local port
$env:SERVER_ADDRESS = "127.0.0.1"
$env:SERVER_PORT = "8088"
$env:PORT = "8088"

# Configure administrator bootstrap parameters
$env:ADMIN_EMAIL = "admin@skillsphere.internal"
$env:ADMIN_FULL_NAME = "System Administrator"

Write-Host "[CHECKPOINT 1 PASS] Session parameters configured with SSL enforced." -ForegroundColor Cyan
```

---

### Checkpoint 2: Secure Masked Credential Entry

Prompt for credentials interactively using `Read-Host -AsSecureString`. Console input is completely hidden (no characters or asterisks are printed).

```powershell
# 1. Prompt for Neon Database Password
Write-Host "Enter Neon Database Password (input is hidden): " -NoNewline -ForegroundColor Yellow
$secDbPassword = Read-Host -AsSecureString

# 2. Prompt for Administrator Password
Write-Host "Enter Production Administrator Password (input is hidden): " -NoNewline -ForegroundColor Yellow
$secAdminPassword = Read-Host -AsSecureString

# 3. Convert SecureStrings to process-scoped environment variables for child processes (psql, mvn)
$bstrDb = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secDbPassword)
$bstrAdmin = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secAdminPassword)
try {
    $env:PGPASSWORD = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstrDb)
    $env:DB_PASSWORD = $env:PGPASSWORD
    $env:ADMIN_PASSWORD = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstrAdmin)
    Write-Host "`n[CHECKPOINT 2 PASS] Credentials loaded into process environment." -ForegroundColor Green
} finally {
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstrDb)
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstrAdmin)
}
```

---

### Checkpoint 3: Pre-Migration Handshake & Full Empty-Target Preflight

Verify database identity, active TLS encryption, and that the target schema contains zero pre-existing user objects (tables, partitioned tables, views, materialized views, sequences, or functions).

```powershell
Write-Host "Executing Pre-Migration Handshake and Target Schema Preflight..." -ForegroundColor Cyan

# 1. Identity and active SSL assertion query
$identityQuery = @"
SELECT
    current_database() AS db_name,
    current_user AS user_name,
    version() AS pg_version,
    (SELECT count(*) FROM pg_stat_ssl WHERE pid = pg_backend_pid() AND ssl = true) AS is_ssl;
"@

$identityResult = & psql -w -c $identityQuery -t -A 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "[FATAL] Pre-migration handshake failed with exit code $LASTEXITCODE. Output: $identityResult"
    return
}

$parts = $identityResult.Trim() -split '\|'
$connDb = $parts[0]
$connUser = $parts[1]
$connVersion = $parts[2]
$connSsl = [int]$parts[3]

Write-Host "  Connected Database: $connDb" -ForegroundColor Gray
Write-Host "  Connected User:     $connUser" -ForegroundColor Gray
Write-Host "  Server Version:     $connVersion" -ForegroundColor Gray
Write-Host "  SSL Connection:     $($connSsl -eq 1)" -ForegroundColor Gray

if ($connDb -ne $env:PGDATABASE -or $connUser -ne $env:PGUSER) {
    Write-Error "[FATAL] Target database or user mismatch! Expected $($env:PGDATABASE)/$($env:PGUSER), got $connDb/$connUser. Aborting."
    return
}
if ($connSsl -ne 1) {
    Write-Error "[FATAL] Connection is not encrypted with active SSL/TLS! Aborting."
    return
}

# 2. Comprehensive Empty Target Preflight (inspect tables, views, matviews, sequences, routines)
$preflightQuery = @"
SELECT
    (SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public') AS table_count,
    (SELECT count(*) FROM information_schema.views WHERE table_schema = 'public') AS view_count,
    (SELECT count(*) FROM pg_matviews WHERE schemaname = 'public') AS matview_count,
    (SELECT count(*) FROM information_schema.sequences WHERE sequence_schema = 'public') AS seq_count,
    (SELECT count(*) FROM information_schema.routines WHERE routine_schema = 'public') AS routine_count;
"@

$preflightResult = & psql -w -c $preflightQuery -t -A 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "[FATAL] Target schema preflight query failed with exit code $LASTEXITCODE. Output: $preflightResult"
    return
}

$pfParts = $preflightResult.Trim() -split '\|'
$tblCount = [int]$pfParts[0]
$viewCount = [int]$pfParts[1]
$mvCount = [int]$pfParts[2]
$seqCount = [int]$pfParts[3]
$rtCount = [int]$pfParts[4]

Write-Host "  Pre-existing Public Objects: Tables=$tblCount, Views=$viewCount, MatViews=$mvCount, Sequences=$seqCount, Routines=$rtCount" -ForegroundColor Gray

$totalUserObjects = $tblCount + $viewCount + $mvCount + $seqCount + $rtCount
if ($totalUserObjects -ne 0) {
    Write-Error "[FATAL] Target public schema is NOT empty ($totalUserObjects existing objects found). Fresh migration must target a clean schema. Aborting."
    return
}

Write-Host "[CHECKPOINT 3 PASS] Handshake successful, TLS active, target schema is 100% empty." -ForegroundColor Green
```

---

### Checkpoint 4: Migration & Bootstrap Execution (Spring Boot Runner)

Execute the full backend migration runner. Spring Boot will execute Flyway migrations V1 through V12, create the 57 domain tables and Flyway history table, insert reference seed data, and invoke `AdminBootstrap` to provision the administrator.

> [!IMPORTANT]
> **Application Runner Instructions**:
> `mvn spring-boot:run` starts the complete Spring Boot application.
> It runs bound to `127.0.0.1:8088` (local loopback only).
> When you see the log message:
> `Started SkillSphereApplication in ... seconds (process running for ...)`
> Press **`Ctrl+C`** in the terminal to stop the application runner.
> Once the process stops and the prompt returns, proceed to Checkpoint 5.

```powershell
Write-Host "Launching Spring Boot Migration Runner against Neon..." -ForegroundColor Cyan
Write-Host "Target JDBC: $env:DB_URL" -ForegroundColor Gray
Write-Host "Wait until 'Started SkillSphereApplication' appears, then press Ctrl+C to terminate the runner.`n" -ForegroundColor Yellow

# Execute Maven Spring Boot Runner
mvn spring-boot:run -Dspring-boot.run.arguments="--server.address=127.0.0.1 --server.port=8088"
```

---

### Checkpoint 5: Independent Post-Migration Schema & Seed Verification

Run this verification block in PowerShell after Checkpoint 4 has completed and the application has stopped.

```powershell
Write-Host "`nExecuting Comprehensive Post-Migration Verifications..." -ForegroundColor Cyan

# 1. Verify Flyway Migration History (must contain exactly 12 successful migrations)
$flywayCheckQuery = "SELECT count(*), bool_and(success) FROM flyway_schema_history;"
$flywayResult = & psql -w -c $flywayCheckQuery -t -A 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "[FATAL] Failed to query flyway_schema_history. Exit code: $LASTEXITCODE. Output: $flywayResult"
    return
}
$fwParts = $flywayResult.Trim() -split '\|'
$fwCount = [int]$fwParts[0]
$fwSuccess = $fwParts[1]

Write-Host "  Flyway History Records: $fwCount (Expected: 12)" -ForegroundColor Gray
Write-Host "  All Migrations Successful: $fwSuccess (Expected: t)" -ForegroundColor Gray

if ($fwCount -ne 12 -or $fwSuccess -ne 't') {
    Write-Error "[ASSERTION FAILED] Flyway migration history assertion failed! Expected 12 successful rows."
    return
}

# 2. Verify Physical BASE TABLE Count (Must equal exactly 58: 57 domain tables + 1 flyway_schema_history)
$tableCountQuery = "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';"
$tableResult = & psql -w -c $tableCountQuery -t -A 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "[FATAL] Table count query failed with exit code $LASTEXITCODE."
    return
}
$actualTableCount = [int]$tableResult.Trim()
Write-Host "  Public BASE TABLE Count: $actualTableCount (Expected: 58)" -ForegroundColor Gray

if ($actualTableCount -ne 58) {
    Write-Error "[ASSERTION FAILED] Table count mismatch! Expected 58, found $actualTableCount."
    return
}

# 3. Verify Exact Catalog Seed Counts
$seedQuery = @"
SELECT
    (SELECT count(*) FROM departments) AS dept_count,
    (SELECT count(*) FROM skills) AS skill_count,
    (SELECT count(*) FROM courses) AS course_count,
    (SELECT count(*) FROM assessment_questions) AS question_count,
    (SELECT count(*) FROM system_configuration) AS config_count;
"@

$seedResult = & psql -w -c $seedQuery -t -A 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "[FATAL] Seed verification query failed with exit code $LASTEXITCODE."
    return
}
$sParts = $seedResult.Trim() -split '\|'
$cntDept   = [int]$sParts[0]
$cntSkill  = [int]$sParts[1]
$cntCourse = [int]$sParts[2]
$cntQuest  = [int]$sParts[3]
$cntConfig = [int]$sParts[4]

Write-Host "  Seed Counts:" -ForegroundColor Gray
Write-Host "    Departments:          $cntDept   (Expected: 4)" -ForegroundColor Gray
Write-Host "    Skills:               $cntSkill  (Expected: 8)" -ForegroundColor Gray
Write-Host "    Courses:              $cntCourse (Expected: 6)" -ForegroundColor Gray
Write-Host "    Assessment Questions: $cntQuest  (Expected: 76)" -ForegroundColor Gray
Write-Host "    System Configuration: $cntConfig (Expected: 4)" -ForegroundColor Gray

if ($cntDept -ne 4 -or $cntSkill -ne 8 -or $cntCourse -ne 6 -or $cntQuest -ne 76 -or $cntConfig -ne 4) {
    Write-Error "[ASSERTION FAILED] Seed record count mismatch! Check migration SQL data."
    return
}

# 4. Verify Initial Administrator Account Provisioning (Section 9.E Assertions)
$adminCountQuery = "SELECT count(*) FROM users WHERE role = 'ADMIN';"
$adminCountResult = & psql -w -c $adminCountQuery -t -A 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "[FATAL] Admin count query failed with exit code $LASTEXITCODE."
    return
}
$adminCount = [int]$adminCountResult.Trim()

if ($adminCount -ne 1) {
    Write-Error "[ASSERTION FAILED] Expected exactly 1 ADMIN user in database, found $adminCount!"
    return
}

# Verify administrator details (do NOT query or print password hashes)
$adminDetailQuery = "SELECT email, role, account_status FROM users WHERE role = 'ADMIN';"
$adminDetailResult = & psql -w -c $adminDetailQuery -t -A 2>&1
$adParts = $adminDetailResult.Trim() -split '\|'
$adminEmail = $adParts[0]
$adminRole = $adParts[1]
$adminStatus = $adParts[2]

$normalizedExpectedEmail = $env:ADMIN_EMAIL.Trim().ToLower()
if ($adminEmail -ne $normalizedExpectedEmail) {
    Write-Error "[ASSERTION FAILED] Admin email mismatch! Expected '$normalizedExpectedEmail', found '$adminEmail'."
    return
}
if ($adminRole -ne "ADMIN") {
    Write-Error "[ASSERTION FAILED] Admin role mismatch! Expected 'ADMIN', found '$adminRole'."
    return
}
if ($adminStatus -ne "ACTIVE") {
    Write-Error "[ASSERTION FAILED] Admin status mismatch! Expected 'ACTIVE', found '$adminStatus'."
    return
}

Write-Host "  Administrator Account Verified:" -ForegroundColor Gray
Write-Host "    Email:          $adminEmail" -ForegroundColor Gray
Write-Host "    Role:           $adminRole" -ForegroundColor Gray
Write-Host "    Account Status: $adminStatus" -ForegroundColor Gray

Write-Host "`n[CHECKPOINT 5 PASS] ALL POST-MIGRATION ASSERTIONS VERIFIED SUCCESSFULLY!" -ForegroundColor Green
```

---

### Checkpoint 6: Session Cleanup & Console Window Sanitization

Clear all process-scoped environment variables and release SecureString memory.

```powershell
# Clear all sensitive and process environment variables
$env:PGPASSWORD = $null
$env:DB_PASSWORD = $null
$env:ADMIN_PASSWORD = $null
$env:ADMIN_EMAIL = $null
$env:ADMIN_FULL_NAME = $null
$env:DB_URL = $null
$env:DB_USERNAME = $null
$env:PGHOST = $null
$env:PGPORT = $null
$env:PGDATABASE = $null
$env:PGUSER = $null
$env:PGSSLMODE = $null
$env:SERVER_ADDRESS = $null
$env:SERVER_PORT = $null
$env:PORT = $null

# Dispose SecureString objects
if ($secDbPassword) { $secDbPassword.Dispose() }
if ($secAdminPassword) { $secAdminPassword.Dispose() }

Write-Host "[CHECKPOINT 6 PASS] Process environment variables cleared." -ForegroundColor Cyan
Write-Host "NOTE: To release all process-allocated memory, close this PowerShell window." -ForegroundColor Yellow
```

> [!NOTE]
> **Forensic Memory Sanitization Clarification**: Setting environment variables to `$null` and disposing SecureStrings removes in-memory variables from the active PowerShell session. However, under Windows, operating system pagefile paging and standard CLR runtime garbage collection memory artifacts cannot guarantee forensic-grade memory erasure. Closing the PowerShell terminal releases the process memory space.

---

## 4. Failure Diagnostics & Recovery Protocol

### 4.1 Understanding PostgreSQL Transactional Rollback
- In PostgreSQL, Flyway executes DDL migrations inside transactional blocks (`BEGIN ... COMMIT`).
- **Transactional Rollback Behavior**: If a migration script fails, PostgreSQL rolls back the entire transaction, which typically includes the insertion of the metadata record into `flyway_schema_history`.
- **Key Realization**: A failed migration will **NOT necessarily leave a `success = false` row** in `flyway_schema_history`. Instead, the table may simply stop at the last successful migration (e.g. V3 if V4 failed).
- Migrations executed prior to the failed version remain committed.

### 4.2 Recovery Rules
1. **Never Routinely Run `flyway repair`**: `flyway repair` is intended only for aligning checksums or removing dangling failed rows; running it blindly without diagnosing root causes can leave an inconsistent schema.
2. **Never Routinely Delete History Rows**: Do NOT execute `DELETE FROM flyway_schema_history WHERE success = false;` unless you have inspected the physical database and verified that no partial objects were created.
3. **Never Drop Databases or Public Schemas**: Under no circumstances should `DROP DATABASE` or `DROP SCHEMA public CASCADE` be executed.
4. **Diagnostic Sequence**:
   - Inspect the Maven build console log for the exact PostgreSQL error message and failing SQL statement.
   - Run a read-only query against `flyway_schema_history` to determine the highest installed version.
   - Compare physical tables against the failing migration script to verify state before taking corrective action.

---

## 5. Pre-Execution Sign-Off Checklist

Before running Checkpoints 1 through 6 against Neon, verify:
- [ ] Neon project created and direct connection host, port, database, and user obtained.
- [ ] Local database (`skillsphere_db` at `127.0.0.1:5432`) is NOT configured in the session.
- [ ] Target Neon database is 100% empty (confirmed by Checkpoint 3 preflight).
- [ ] PostgreSQL client tools (`psql.exe`) are available.
- [ ] Operator has reviewed the Spring Boot run and graceful termination instructions (`Ctrl+C`).
