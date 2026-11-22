# SkillSphere — Free-Tier Production Deployment Runbook

**Document Version:** 2.0.0
**Project:** SkillSphere (Infosys Springboard Virtual Internship 7.0)
**Target Infrastructure:** Vercel (Frontend), Render (Backend via Docker), Neon (PostgreSQL Free Tier)
**Readiness Status:** **`BLOCKED — USER DECISIONS REQUIRED PRIOR TO LIVE DEPLOYMENT`**
**Date Checked:** November 22, 2026

---

## 1. Architecture Overview & Flow

SkillSphere free-tier cloud deployment utilizes a same-origin reverse-proxy architecture to eliminate cross-origin cookie, session, and CORS complexities.

```
+-------------------------------------------------------------------------+
|                               Browser Client                            |
+-------------------------------------------------------------------------+
                                     |
                                     | Same-origin HTTPS requests
                                     v
+-------------------------------------------------------------------------+
|                    Vercel Edge Network (Frontend SPA)                   |
|  - Serves compiled React 18 / Vite SPA assets                           |
|  - Reverse-proxies /api/* to Render backend service via vercel.json     |
+-------------------------------------------------------------------------+
                                     |
                                     | Backend-to-Backend HTTPS Proxy
                                     v
+-------------------------------------------------------------------------+
|                    Render Web Service (Backend API)                     |
|  - Containerized Spring Boot 3.3.5 on Java 21 (Dockerfile)              |
|  - Binds to 0.0.0.0 on assigned $PORT                                   |
|  - Session management: HttpOnly, SameSite=Lax, Secure cookies           |
+-------------------------------------------------------------------------+
                                     |
                                     | PostgreSQL TLS (sslmode=require)
                                     v
+-------------------------------------------------------------------------+
|                Neon Serverless PostgreSQL (Database)                    |
|  - PostgreSQL 18-compatible serverless database                         |
|  - 0.5 GiB storage, auto-suspend after 5 min idle                       |
|  - 58 physical tables (57 domain + 1 Flyway history)                    |
+-------------------------------------------------------------------------+
```

### Key Architectural Benefits:
1. **Cookie Consistency**: Because the browser communicates exclusively with the Vercel domain (`https://<your-project>.vercel.app`), session and CSRF cookies are stored and sent under same-origin rules.
2. **Zero Hardcoded Backend URLs**: The production frontend bundle contains zero references to `localhost:8080` or hardcoded backend domains.
3. **Containerized Port Agility**: The backend binds dynamically to Render's injected `$PORT` on `0.0.0.0`.

---

## 2. Platform Constraints & Verified Official Limitations

Before provisioning or deploying, the following hosting provider constraints must be understood:

### 2.1 Render Free Outbound SMTP Port Blocking (CRITICAL BLOCKER)
- **Official Source**: `https://render.com/docs/free#outbound-smtp` (Verified November 2026)
- **Verified Policy**: Render blocks outbound network connections on SMTP ports 25, 465, and 587 on all Free tier web services to prevent spam abuse.
- **Impact on SkillSphere**: SkillSphere’s OTP password reset service (`SmtpEmailService`) uses `smtp.gmail.com:587` with STARTTLS. On Render Free, outbound connections will hang and fail with socket timeouts.
- **Status**: **`BLOCKED — USER DECISION REQUIRED`**
- **Decision Pathways**:
  - **Option A (Hosting Upgrade)**: Upgrade the Render Web Service to a paid instance ($7/month "Starter" tier), which permits outbound SMTP on ports 465 and 587.
  - **Option B (HTTP REST Email API)**: Implement an email adapter using an HTTPS-based REST API (e.g., Resend, Brevo, SendGrid API over port 443), which is fully supported on Render Free.
  - **Option C (Alternative Staging Mode)**: Run non-production staging with `app.password-reset.log-links-to-console=true` or use an alternative hosting platform allowing free SMTP.

### 2.2 Chat Attachment Storage (Ephemeral Disk Limitation)
- **Official Source**: `https://render.com/docs/free#free-web-services` (Verified November 2026)
- **Verified Policy**: Free web services on Render have ephemeral filesystems. Any files written to disk are destroyed when the instance sleeps (after 15 minutes of inactivity) or restarts.
- **Impact on SkillSphere**: `ChatAttachmentService.java` stores files in `uploads/chat`. Files uploaded during chat will not persist across container spin-downs.
- **Status**: **`KNOWN LIMITATION (ACCEPTABLE FOR PROTOTYPE / FUTURE ROADMAP: CLOUDFLARE R2 / S3)`**

### 2.3 Neon Free Tier Limits
- **Official Source**: `https://neon.tech/docs/introduction/plans#free-plan` (Verified November 2026)
- **Limits**: 0.5 GiB storage, scale-to-zero compute after 5 minutes idle.
- **Connection Mode**: Initial Flyway migrations **must use the direct (non-pooled) connection string on port 5432**. Connection pooling (port 6543) does not support Flyway transactional DDL or advisory locks.

---

## 3. Phase 1 — Database Migration (Neon Serverless PostgreSQL)

Follow the authoritative execution instructions in `docs/SKILLSPHERE_NEON_FINAL_VERIFIED_EXECUTION_GUIDE.md`.

### Execution Summary:
1. Create a free project in the Neon Console.
2. Note the direct connection parameters (host, port 5432, database, user).
3. Open Windows PowerShell, navigate to `skillsphere/backend`.
4. Run Checkpoint 1 (parameter setup) and Checkpoint 2 (secure masked credential entry).
5. Run Checkpoint 3 (handshake and empty-target preflight).
6. Run Checkpoint 4 (Spring Boot migration runner on `127.0.0.1:8088`).
7. Press `Ctrl+C` after `Started SkillSphereApplication` appears.
8. Run Checkpoint 5 (post-migration assertions: 12 Flyway records, 58 tables, exact reference seeds, exactly 1 active admin).
9. Run Checkpoint 6 (clear session environment variables).

---

## 4. Phase 2 — Backend Deployment (Render Web Service)

Deploy the backend using Render's Docker Web Service capability.

### 4.1 Deployment Setup
1. In the Render Dashboard, select **New +** -> **Web Service**.
2. Connect your Git repository (`https://github.com/tanishamane/SkillSphere`).
3. Configure service parameters:
   - **Name**: `skillsphere-backend`
   - **Region**: Same region as Neon (e.g. `Ohio (US East)`)
   - **Branch**: `team1-jayanth-transfer` (or target release branch)
   - **Root Directory**: `skillsphere/backend`
   - **Environment / Runtime**: `Docker`
   - **Dockerfile Path**: `Dockerfile` (uses `skillsphere/backend/Dockerfile`)
   - **Instance Type**: `Free` (or `Starter` if Option A for SMTP is chosen)

### 4.2 Environment Variable Inventory (Render Dashboard)

Configure the following environment variables in the Render Dashboard (never commit real values):

| Variable Name | Required Value / Format | Purpose |
| :--- | :--- | :--- |
| **`DB_URL`** | `jdbc:postgresql://<NEON_HOST>:5432/<NEON_DB>?sslmode=require` | Neon direct JDBC connection string |
| **`DB_USERNAME`** | `<NEON_USER>` | Neon database user |
| **`DB_PASSWORD`** | `<NEON_PASSWORD>` | Neon database password |
| **`FRONTEND_URL`** | `https://<YOUR_VERCEL_PROJECT>.vercel.app` | Vercel frontend URL |
| **`CORS_ALLOWED_ORIGINS`** | `https://<YOUR_VERCEL_PROJECT>.vercel.app` | Permitted browser origins |
| **`SESSION_COOKIE_SECURE`** | `true` | Enforces Secure flag on session cookies |
| **`COOKIE_SECURE`** | `true` | Enforces Secure flag on CSRF cookies |
| **`SESSION_COOKIE_SAME_SITE`**| `lax` | Permitted for same-origin proxy |
| **`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`** | `5` | Keeps connection count low on Neon Free |
| **`SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE`** | `0` | Allows idle connections to drain |
| **`SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT`** | `30000` | 30s timeout handles Neon cold-start |

*(Note: SMTP variables depend on the user decision made for Section 2.1)*

### 4.3 Health Check Configuration
- **Health Check Path**: `/api/health`
- Render periodically polls `/api/health` to confirm container readiness.

---

## 5. Phase 3 — Frontend Deployment (Vercel)

Deploy the frontend SPA using Vercel.

### 5.1 Update Vercel Reverse Proxy Destination
In `skillsphere/frontend/vercel.json`, update the placeholder `<BACKEND_HOST>` with your actual Render backend domain (e.g. `skillsphere-backend.onrender.com`):

```json
{
  "version": 2,
  "rewrites": [
    {
      "source": "/api/:path*",
      "destination": "https://skillsphere-backend.onrender.com/api/:path*"
    },
    {
      "source": "/(.*)",
      "destination": "/index.html"
    }
  ]
}
```

### 5.2 Deploy to Vercel
1. In the Vercel Dashboard, select **Add New...** -> **Project**.
2. Import the Git repository.
3. Configure Project Settings:
   - **Framework Preset**: `Vite`
   - **Root Directory**: `skillsphere/frontend`
   - **Build Command**: `npm run build`
   - **Output Directory**: `dist`
4. **Environment Variables**:
   - Leave `VITE_API_BASE_URL` **UNSET** (or empty).
   - In production, Axios will default to `""` (root-relative), routing all `/api/*` requests through the Vercel proxy.
5. Click **Deploy**.

---

## 6. Phase 4 — Production Smoke Testing & Verification

Once both services are deployed, perform these verification tests:

| Step | Test Name | Request / Action | Expected Result |
| :---: | :--- | :--- | :--- |
| **1** | **Backend Health Check** | `GET https://<RENDER_BACKEND>/api/health` | HTTP 200: `{"status":"UP","application":"SkillSphere"}` |
| **2** | **Vercel Same-Origin Proxy** | `GET https://<VERCEL_APP>/api/health` | HTTP 200: `{"status":"UP","application":"SkillSphere"}` |
| **3** | **CSRF Handshake** | `GET https://<VERCEL_APP>/api/auth/csrf` | HTTP 200: `XSRF-TOKEN` cookie returned with `Path=/` |
| **4** | **Admin Sign-In** | `POST https://<VERCEL_APP>/api/auth/login` | HTTP 200: `JSESSIONID` cookie returned with `HttpOnly; Secure; SameSite=Lax` |
| **5** | **Session Verification** | `GET https://<VERCEL_APP>/api/auth/me` | HTTP 200: Returns admin profile JSON |
| **6** | **SPA Client Routing** | Refresh browser on `/admin/dashboard` | HTTP 200: Admin dashboard renders (no 404) |
| **7** | **Password Reset OTP Flow** | `POST https://<VERCEL_APP>/api/auth/forgot-password` | Verified per chosen email delivery architecture |

---

## 7. Outstanding User Decisions Required Before Deployment

Deployment to live infrastructure cannot be completed until the project owner decides on:
1. **SMTP Delivery Architecture**: Selection between Option A (Render Paid tier for Gmail SMTP), Option B (HTTPS REST email API like Resend/Brevo), or Option C (Staging mock mode).
2. **Backend Domain Identification**: Actual Render URL needed to complete the `vercel.json` destination before pushing to Vercel.
3. **Chat Attachment Storage**: Acceptance of ephemeral disk on free tier or scheduling Cloudflare R2 integration for production.
