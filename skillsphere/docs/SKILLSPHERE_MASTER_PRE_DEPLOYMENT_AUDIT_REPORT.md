# SkillSphere — Master Pre-Deployment Audit Report

**Document Version:** 2.0.0
**Project:** SkillSphere (Infosys Springboard Virtual Internship 7.0)
**Repository Root:** `C:\Users\gjaya\OneDrive\Desktop\SkillSphere`
**Application Directory:** `C:\Users\gjaya\OneDrive\Desktop\SkillSphere\skillsphere`
**Current Branch:** `team1-jayanth-transfer`
**HEAD Commit:** `ee7a8e29c6018ce673481d8475a5a1e8f54d59d7` (`feat: add secure email OTP password reset`)
**Remotes:**
- `origin`: `https://github.com/tanishamane/SkillSphere.git`
- `mentor`: `https://github.com/springboardmentor81848x-lang/Organizational-platform.git`
**Local Database Baseline:** `skillsphere_db` on PostgreSQL 18.4 at `127.0.0.1:5432` (untouched)
**Audit Date:** November 22, 2026

---

## 1. Executive Summary & Audit Scope

This comprehensive audit consolidates the pre-deployment code verification, configuration analysis, database migration integrity, and cloud platform compatibility for the SkillSphere application prior to free-tier cloud deployment.

### Strict Safety & Verification Status
- **Zero Remote Actions**: No connections were initiated to Neon, Render, Vercel, or any external remote database.
- **Local Database Untouched**: The local PostgreSQL 18.4 instance (`skillsphere_db`) was independently confirmed untouched. Zero tables, rows, or schemas were altered.
- **Zero Secrets Disclosed**: No database passwords, JWT secrets, SMTP credentials, or live connection strings exist in tracked files, reports, or console output.
- **Zero Accidental Commits**: No Git staging, commit, push, branch change, or index modification was performed.
- **Honest Platform Assessment**: Identified two critical deployment-blocking constraints (Render Free outbound SMTP port blocking and ephemeral container attachment storage) that must be resolved by user decisions before live deployment.

---

## 2. Git Repository & Working-Tree Baseline

| Parameter | Observed Status | Evidence / Verification |
| :--- | :--- | :--- |
| **Active Branch** | `team1-jayanth-transfer` | `git branch --show-current` |
| **HEAD Commit** | `ee7a8e29c6018ce673481d8475a5a1e8f54d59d7` | `git rev-parse HEAD` (Matches expected baseline) |
| **Remote Sync** | In sync with `origin/team1-jayanth-transfer` and `mentor/team1-jayanth` | `git remote -v` |
| **Staging Area (Index)** | **Clean (0 files staged)** | `git diff --cached` (Empty) |
| **Working Tree Status** | **Modified (7 tracked files modified, untracked additions)** | `git status --short` |
| **Tracked Changes** | 7 files modified for pre-deployment hardening & routing | `backend/.env.example`<br>`backend/src/main/resources/application.yml`<br>`backend/src/main/java/com/skillsphere/config/SecurityConfig.java`<br>`backend/src/main/java/com/skillsphere/service/PostgresNativeClient.java`<br>`frontend/src/services/apiClient.js`<br>`frontend/src/services/authService.js`<br>`frontend/src/services/mentorshipService.js` |
| **Untracked Additions** | Docker, routing template, health check & tests, documentation | `backend/Dockerfile`<br>`backend/.dockerignore`<br>`backend/src/main/java/com/skillsphere/controller/HealthController.java`<br>`backend/src/test/java/com/skillsphere/controller/HealthControllerSecurityTest.java`<br>`backend/src/test/java/com/skillsphere/service/PostgresNativeClientBinaryResolutionTest.java`<br>`frontend/vercel.json`<br>`docs/*.md` |
| **Diff Cleanliness** | 0 whitespace errors, 0 conflict markers | `git diff --check` (Exit code 0) |

> [!IMPORTANT]
> **Working-Tree vs. Staging Area Clarification**: The Git staging area (index) is completely clean (zero files staged for commit). The working tree contains verified pre-deployment hardening modifications and untracked deployment artifacts. Nothing has been committed or pushed.

---

## 3. Backend Codebase & Dependency Audit

### 3.1 Technology Stack & Versions
- **Java**: 21 (Eclipse Temurin JDK 21.0.12.1 verified)
- **Spring Boot**: 3.3.5
- **Spring Security**: 6.3.4 (CSRF via `CookieCsrfTokenRepository.withHttpOnlyFalse()`, session fixation protection via `changeSessionId()`)
- **Database Driver**: `org.postgresql:postgresql:42.7.4`
- **Flyway**: `org.flywaydb:flyway-core:10.10.0` and `org.flywaydb:flyway-database-postgresql:10.10.0`
- **Build Tool**: Apache Maven 3.9.9

### 3.2 Automated Test Suite Results
Command executed: `mvn -f backend/pom.xml test`
- **Test Count**: **443 tests run**
- **Failures**: **0**
- **Errors**: **0**
- **Skipped**: **0**
- **Build Status**: **BUILD SUCCESS**
- **Exit Code**: **0**
- **Execution Time**: 1 min 53 s

### 3.3 Packaging Verification
Command executed: `mvn -f backend/pom.xml package -DskipTests`
- **Target Artifact**: `backend/target/skillsphere-backend-0.1.0.jar` (repackaged executable Spring Boot archive)
- **Build Status**: **BUILD SUCCESS**
- **Exit Code**: **0**

---

## 4. Frontend Codebase & Build Audit

### 4.1 Technology Stack & Bundling
- **React**: 18.3.1
- **Vite**: 5.4.21
- **Router**: React Router DOM 6.28.0
- **HTTP Client**: Axios 1.7.9

### 4.2 Production Build Verification
Command executed: `npm --prefix frontend run build`
- **Modules Transformed**: **200 modules**
- **Build Status**: **SUCCESS**
- **Exit Code**: **0**
- **Output Artifacts**:
  - `dist/index.html` (0.86 kB)
  - `dist/assets/index-B2IbNO2K.css` (90.82 kB)
  - `dist/assets/index-CJe0iozH.js` (874.77 kB)

### 4.3 Production Asset Inspection for Hardcoded URLs
- **Bundle Inspected**: `dist/assets/index-CJe0iozH.js`
- **Check for `localhost:8080`**: **0 occurrences found (CLEAN)**
- **Verification Evidence**: The production build bundle contains zero references to `http://localhost:8080` or hardcoded backend origins.

### 4.4 API BaseURL Correction & Vercel Same-Origin Proxy
Previous audits discovered an architectural contradiction where Axios defaulted to `http://localhost:8080` even in production when `VITE_API_BASE_URL` was omitted.

**Correction Applied Across All Axios Instances**:
In `apiClient.js`, `authService.js`, and `mentorshipService.js`:
```javascript
baseURL: import.meta.env.VITE_API_BASE_URL || (import.meta.env.DEV ? "http://localhost:8080" : ""),
```
- **In Development (`DEV = true`)**: Defaults to `http://localhost:8080` if `VITE_API_BASE_URL` is unset, preserving developer ergonomics.
- **In Production (`DEV = false`)**: Defaults to `""` (empty string, root-relative `/api/*`), routing API requests same-origin to the Vercel hosting domain.
- **Vercel Reverse Proxy Configuration**: Created `skillsphere/frontend/vercel.json` with rewrite rules:
  ```json
  {
    "version": 2,
    "rewrites": [
      {
        "source": "/api/:path*",
        "destination": "https://<BACKEND_HOST>/api/:path*"
      },
      {
        "source": "/(.*)",
        "destination": "/index.html"
      }
    ]
  }
  ```
  This eliminates cross-origin cookie and CORS issues in the browser, routing all authenticated sessions same-origin through Vercel to the backend.

---

## 5. Database Schema & Migration Verification

### 5.1 Migration File Sequence
All 12 Flyway migration files were verified in `backend/src/main/resources/db/migration`:
1. `V1__create_users.sql` (Creates `users` table)
2. `V2__create_password_reset_tokens.sql` (Creates `password_reset_tokens` table)
3. `V3__create_mentorship_tables.sql` (Creates mentorship and profile tables)
4. `V4__complete_platform_schema.sql` (Creates core tables and initial seed data)
5. `V5__fix_schema_validation_columns.sql` (Schema alignment and column corrections)
6. `V6__dynamic_assessment_enhancements.sql` (Assessment questions, options, difficulty)
7. `V7__manager_dashboard_enhancements.sql` (Manager dashboard views and recommendations)
8. `V8__mentor_dashboard_enhancements.sql` (Mentor scheduling, availability, feedback)
9. `V9__hr_dashboard_enhancements.sql` (Announcements, ticketing, training programmes)
10. `V10__chat_enhancements.sql` (Conversations, messages, attachments, participant tracking)
11. `V11__add_cancelled_to_training_programmes.sql` (Training status enum update)
12. `V12__create_password_reset_otps.sql` (OTP cryptographic table)

### 5.2 Physical Table Count Derivation
- **Domain Tables Created by Migrations**: **57 tables**
  `assessment_assignments`, `assessment_attempts`, `assessment_questions`, `assessment_responses`, `assessment_submissions`, `audit_logs`, `calendar_events`, `certificates`, `conversation_participants`, `conversations`, `courses`, `departments`, `discussion_replies`, `discussion_threads`, `employee_learning`, `employee_profiles`, `employee_skills`, `event_participants`, `goal_action_items`, `hr_announcement_reads`, `hr_announcements`, `knowledge_articles`, `knowledge_categories`, `leadership_report_shares`, `learning_path_items`, `learning_paths`, `manager_course_recommendations`, `mentee_skill_consents`, `mentor_assignments`, `mentor_availability`, `mentor_evaluations`, `mentor_profiles`, `mentorship_feedback`, `mentorship_goals`, `mentorship_requests`, `mentorship_sessions`, `message_reports`, `messages`, `notifications`, `password_reset_otps`, `password_reset_tokens`, `platform_feedback`, `reassessment_requests`, `role_skill_requirements`, `shared_resources`, `skill_assessments`, `skill_categories`, `skill_evidence`, `skill_gaps`, `skills`, `support_tickets`, `system_configuration`, `team_memberships`, `teams`, `training_enrollments`, `training_programmes`, `users`.
- **Flyway Metadata Table**: **1 table** (`flyway_schema_history`)
- **Total Physical BASE TABLE Count in `public` schema**: **58 tables** (57 domain + 1 Flyway history)

### 5.3 Verified Static Reference Seed Counts
Verified directly from migration SQL scripts:
- **Departments**: **4 rows** (`Engineering`, `Quality Assurance`, `Data & Analytics`, `Human Resources` in V4)
- **Skills**: **8 rows** (`Java`, `Spring Boot`, `Web Development`, `SQL`, `Automated Testing`, `Git and Version Control`, `Data Structures`, `Problem Solving` in V4)
- **Courses**: **6 rows** (`Enterprise Java 17 Masterclass`, `Spring Boot 3 & Microservices Architecture`, `High-Performance PostgreSQL & SQL Tuning`, `Full-Stack Web Development with React`, `Git Workflow & Branching for Engineering Teams`, `Data Structures & Algorithms in Practice` in V4; *corrected from prior report's inaccurate count of 5*)
- **Assessment Questions**: **76 rows** (12 in V4 + 64 in V6)
- **System Configuration**: **4 rows** (`max_upload_size_mb`, `allowed_attachment_types`, `auto_archive_inactive_mentorship_days`, `chat_retention_days` in V4)

### 5.4 Administrator Account Bootstrap Mechanics
- **Migrations V1–V12**: Create zero user rows. The `users` table is created empty.
- **Spring Boot `AdminBootstrap.java`**: Implements `CommandLineRunner`. On first application startup, if `ADMIN_EMAIL` and `ADMIN_PASSWORD` are provided and no user with role `ADMIN` exists, it bootstraps exactly one administrator user with role `ADMIN` and status `ACTIVE`.
- **Assertion**: Post-bootstrap database must contain **exactly 1 user** with role `ADMIN`.

---

## 6. Production Configuration & Free-Tier Platform Compatibility

### 6.1 Backend Hosting (Render Compatibility)
- **Java Runtime**: Render does not offer a native Java runtime. A multi-stage `backend/Dockerfile` using Eclipse Temurin JRE 21 and a `.dockerignore` file were created.
- **Port Binding**: Render injects `$PORT`. `application.yml` was updated to `port: ${PORT:${SERVER_PORT:8080}}` and `address: ${SERVER_ADDRESS:0.0.0.0}` to ensure proper interface and port binding in production.
- **Health Check Endpoint**: `/api/health` returns `{"status":"UP","application":"SkillSphere"}` without requiring authentication (status: **PASS**).
- **Scale-to-Zero / Sleep**: Free web services on Render sleep after 15 minutes of inactivity; initial wakeup takes ~30–50 seconds.

### 6.2 Render Free Outbound SMTP Port Blocking (CRITICAL)
- **Official Restriction**: Render official documentation (`https://render.com/docs/free#outbound-smtp`, verified November 2026) confirms that Render Free blocks all outbound traffic on SMTP ports 25, 465, and 587 to prevent platform spam abuse.
- **Impact on SkillSphere**: SkillSphere's `SmtpEmailService` connects to `smtp.gmail.com:587` using STARTTLS. On Render Free, all OTP password reset email attempts will fail with a socket connection timeout.
- **Status**: **`BLOCKED — USER DECISION REQUIRED`**
- **User Options**:
  1. *Option A (Hosting Upgrade)*: Upgrade Render to a paid instance tier ($7/month) where outbound SMTP ports are unrestricted.
  2. *Option B (HTTP Email API)*: Replace direct SMTP with an HTTPS REST email provider (e.g. Resend, Brevo, SendGrid API over port 443) which is permitted on Render Free.
  3. *Option C (Alternative Host)*: Select a backend host that permits outbound SMTP on free tiers, or run staging with dev/mock email logging.

### 6.3 Chat Attachment Ephemeral Storage Limitation
- **Implementation**: `ChatAttachmentService.java` writes uploaded attachments directly to local filesystem storage (`uploads/chat`).
- **Free Container Behavior**: On Render Free, the local filesystem is ephemeral and recreated on every sleep/wake cycle or redeployment. Uploaded chat files will be lost across container restarts.
- **Status**: **`BLOCKED (DOCUMENTED KNOWN LIMITATION)`**
- **Production Recommendation**: Integrate cloud object storage (e.g., Cloudflare R2, AWS S3) for persistent chat attachments in production.

### 6.4 Neon PostgreSQL Free Tier Compatibility
- **Storage Allowance**: 0.5 GiB (more than sufficient for SkillSphere schema and seed data).
- **Compute Allowance**: Neon Free computes scale to zero after 5 minutes of idle time. Cold-start connection latency (~1–3 seconds) is accommodated by Hikari connection timeout.
- **Connection Mode**: Flyway schema migrations must use the **direct connection string (port 5432)**, NOT the PgBouncer pooled connection (port 6543), because Flyway uses session-level locks and transactional DDL incompatible with transaction pooling.

---

## 7. Audit Verdicts by Category

| Category | Status | Details |
| :--- | :---: | :--- |
| **Backend Code & Tests** | **PASS** | 443 tests run, 0 failures, 0 errors. JAR packages cleanly. |
| **Frontend Code & Build** | **PASS** | 200 modules transformed, 0 build errors. 0 `localhost:8080` in bundle. |
| **Frontend API Routing** | **PASS** | Axios baseURL updated; Vercel same-origin `/api/*` reverse proxy configured. |
| **Database Migrations V1–V12** | **PASS** | 57 domain tables + 1 Flyway history table = 58 tables verified. |
| **Seed Data Derivation** | **PASS** | 8 skills, 6 courses, 76 questions, 4 departments, 4 system configs verified. |
| **Admin Provisioning Logic** | **PASS** | `AdminBootstrap` provisions exactly 1 admin when credentials are provided. |
| **Backend Docker Configuration** | **PASS** | Multi-stage Dockerfile and `.dockerignore` created for Java 21 on Render. |
| **Backend Port/Address Binding** | **PASS** | Configured to bind to `0.0.0.0` and listen on `${PORT}`. |
| **Production OTP Email Delivery** | **BLOCKED** | Render Free blocks outbound SMTP ports 25, 465, 587. User decision required. |
| **Chat Attachment Persistence** | **BLOCKED** | Ephemeral container filesystem wipes local attachments on container sleep. |
| **Neon Remote Migration Execution** | **NOT TESTED** | Read-only audit; zero remote connections initiated to Neon. |

---

## 8. Final Consolidated Verdict

### Overall Verdict: **`BLOCKED — USER DECISIONS REQUIRED PRIOR TO DEPLOYMENT`**

The codebase, test suite, build artifacts, and migration definitions are verified and functionally sound. However, deployment to free-tier cloud infrastructure cannot proceed to a "READY FOR DEPLOYMENT" state until the user resolves:
1. **Email Delivery Provider**: Choice between upgrading to a host/tier permitting outbound SMTP (Option A), implementing an HTTPS REST email API like Resend (Option B), or running staging with mock email logging (Option C).
2. **Backend Destination URL**: Once the backend service is provisioned on Render or an alternative host, updating `skillsphere/frontend/vercel.json` with the real backend destination URL.
3. **Chat Attachment Storage**: Acceptance of ephemeral attachment storage on free containers, or approval to integrate S3-compatible cloud storage.
