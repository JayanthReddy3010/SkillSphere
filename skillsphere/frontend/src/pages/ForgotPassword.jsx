import { useState, useEffect } from "react";
import { Link, useNavigate } from "react-router-dom";
import AuthLayout from "../components/AuthLayout.jsx";
import FormInput from "../components/FormInput.jsx";
import PasswordInput from "../components/PasswordInput.jsx";
import PasswordStrength, { scorePassword } from "../components/PasswordStrength.jsx";
import Alert from "../components/Alert.jsx";
import Spinner from "../components/Spinner.jsx";
import {
  requestOtp,
  resendOtp,
  verifyOtp,
  resetPassword,
  parseApiError
} from "../services/authService.js";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const OTP_RE = /^\d{6}$/;

export default function ForgotPassword() {
  const navigate = useNavigate();

  // Wizard state: 1: EMAIL, 2: OTP, 3: PASSWORD, 4: SUCCESS
  const [step, setStep] = useState(1);
  const [email, setEmail] = useState("");
  const [otp, setOtp] = useState("");
  const [resetToken, setResetToken] = useState("");
  const [passwordForm, setPasswordForm] = useState({ password: "", confirmPassword: "" });

  const [errors, setErrors] = useState({});
  const [apiError, setApiError] = useState("");
  const [apiSuccess, setApiSuccess] = useState("");
  const [submitting, setSubmitting] = useState(false);

  // Timers: Resend cooldown (60s) and OTP validity countdown (300s = 5m)
  const [resendCooldown, setResendCooldown] = useState(0);
  const [otpExpiresIn, setOtpExpiresIn] = useState(300);

  // Countdown effect for resend cooldown
  useEffect(() => {
    if (resendCooldown <= 0) return;
    const timer = setInterval(() => {
      setResendCooldown((prev) => (prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => clearInterval(timer);
  }, [resendCooldown]);

  // Countdown effect for OTP expiration
  useEffect(() => {
    if (step !== 2 || otpExpiresIn <= 0) return;
    const timer = setInterval(() => {
      setOtpExpiresIn((prev) => (prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => clearInterval(timer);
  }, [step, otpExpiresIn]);

  const formatTimer = (seconds) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs < 10 ? "0" : ""}${secs}`;
  };

  // --- Step 1: Request OTP ---
  const handleRequestOtp = async (e) => {
    e.preventDefault();
    setApiError("");
    setApiSuccess("");
    const trimmed = email.trim();
    if (!trimmed) {
      setErrors({ email: "Enter your email address." });
      return;
    }
    if (!EMAIL_RE.test(trimmed)) {
      setErrors({ email: "Enter a valid email address." });
      return;
    }
    setErrors({});
    setSubmitting(true);
    try {
      const res = await requestOtp(trimmed);
      setApiSuccess(res.message || "A 6-digit verification code has been sent.");
      setStep(2);
      setResendCooldown(60);
      setOtpExpiresIn(300);
    } catch (err) {
      const parsed = parseApiError(err);
      setApiError(parsed.message);
      setErrors(parsed.fieldErrors || {});
    } finally {
      setSubmitting(false);
    }
  };

  // --- Step 2: Resend OTP ---
  const handleResendOtp = async () => {
    if (resendCooldown > 0 || submitting) return;
    setApiError("");
    setApiSuccess("");
    setSubmitting(true);
    try {
      const res = await resendOtp(email.trim());
      setApiSuccess(res.message || "A fresh verification code has been sent.");
      setResendCooldown(60);
      setOtpExpiresIn(300);
      setOtp("");
    } catch (err) {
      const parsed = parseApiError(err);
      setApiError(parsed.message);
    } finally {
      setSubmitting(false);
    }
  };

  // --- Step 2: Verify OTP ---
  const handleVerifyOtp = async (e) => {
    e.preventDefault();
    setApiError("");
    setApiSuccess("");
    const trimmedOtp = otp.trim();
    if (!trimmedOtp) {
      setErrors({ otp: "Enter the 6-digit verification code." });
      return;
    }
    if (!OTP_RE.test(trimmedOtp)) {
      setErrors({ otp: "Code must be exactly 6 digits." });
      return;
    }
    if (otpExpiresIn <= 0) {
      setApiError("Verification code has expired. Please request a new code.");
      return;
    }

    setErrors({});
    setSubmitting(true);
    try {
      const res = await verifyOtp({ email: email.trim(), otp: trimmedOtp });
      setResetToken(res.resetToken);
      setApiSuccess("Code verified successfully. Please set your new password.");
      setStep(3);
    } catch (err) {
      const parsed = parseApiError(err);
      setApiError(parsed.message);
      setErrors(parsed.fieldErrors || {});
    } finally {
      setSubmitting(false);
    }
  };

  // --- Step 3: Set New Password ---
  const handlePasswordChange = (e) => {
    const { name, value } = e.target;
    setPasswordForm((prev) => ({ ...prev, [name]: value }));
    setErrors((prev) => ({ ...prev, [name]: undefined }));
  };

  const handleResetPassword = async (e) => {
    e.preventDefault();
    setApiError("");
    setApiSuccess("");
    const er = {};
    if (!scorePassword(passwordForm.password).valid) {
      er.password = "Use 8-72 characters with upper and lower case letters, a number, and a symbol.";
    }
    if (passwordForm.confirmPassword !== passwordForm.password) {
      er.confirmPassword = "Passwords do not match.";
    }
    setErrors(er);
    if (Object.keys(er).length > 0) return;

    setSubmitting(true);
    try {
      await resetPassword({
        token: resetToken,
        password: passwordForm.password,
        confirmPassword: passwordForm.confirmPassword
      });
      setStep(4);
    } catch (err) {
      const parsed = parseApiError(err);
      setApiError(parsed.message);
      setErrors(parsed.fieldErrors || {});
    } finally {
      setSubmitting(false);
    }
  };

  const getSubtitle = () => {
    switch (step) {
      case 1:
        return "Enter your work email and we will send you a 6-digit verification code.";
      case 2:
        return `Enter the 6-digit code sent to ${email}.`;
      case 3:
        return "Create a new strong password for your SkillSphere account.";
      case 4:
        return "Your password has been successfully updated.";
      default:
        return "";
    }
  };

  return (
    <AuthLayout
      title="Reset Password"
      subtitle={getSubtitle()}
      footer={
        step !== 4 ? (
          <Link to="/login">Back to sign in</Link>
        ) : null
      }
    >
      {/* SCREEN 1: Enter Registered Email */}
      {step === 1 && (
        <form onSubmit={handleRequestOtp} noValidate className="form">
          <div className="step-badge">Step 1 of 3 &bull; Identify Account</div>
          <Alert type="error">{apiError}</Alert>
          <FormInput
            label="Work email address"
            type="email"
            name="email"
            autoComplete="email"
            placeholder="you@company.com"
            value={email}
            onChange={(e) => {
              setEmail(e.target.value);
              setErrors({});
            }}
            error={errors.email}
          />
          <button
            type="submit"
            className="btn btn--primary"
            disabled={submitting}
            aria-busy={submitting}
          >
            {submitting ? <><Spinner /> Sending code…</> : "Send Verification Code"}
          </button>
        </form>
      )}

      {/* SCREEN 2: Verify OTP */}
      {step === 2 && (
        <form onSubmit={handleVerifyOtp} noValidate className="form">
          <div className="step-badge">Step 2 of 3 &bull; Enter Code</div>
          <Alert type="error">{apiError}</Alert>
          {apiSuccess && <Alert type="success">{apiSuccess}</Alert>}

          <div className="otp-field">
            <FormInput
              label="6-Digit Verification Code"
              type="text"
              name="otp"
              inputMode="numeric"
              maxLength={6}
              autoComplete="one-time-code"
              placeholder="000000"
              value={otp}
              onChange={(e) => {
                const val = e.target.value.replace(/\D/g, "").slice(0, 6);
                setOtp(val);
                setErrors({});
              }}
              error={errors.otp}
            />
          </div>

          <div className="otp-meta">
            <span className="otp-timer">
              Expires in: <strong>{formatTimer(otpExpiresIn)}</strong>
            </span>
            <button
              type="button"
              className="btn--resend"
              onClick={handleResendOtp}
              disabled={resendCooldown > 0 || submitting}
            >
              {resendCooldown > 0 ? `Resend code in ${resendCooldown}s` : "Resend code"}
            </button>
          </div>

          <button
            type="submit"
            className="btn btn--primary"
            disabled={submitting || otp.length !== 6 || otpExpiresIn <= 0}
            aria-busy={submitting}
          >
            {submitting ? <><Spinner /> Verifying…</> : "Verify Code"}
          </button>

          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => {
              setStep(1);
              setOtp("");
              setApiError("");
              setApiSuccess("");
            }}
          >
            Change email address
          </button>
        </form>
      )}

      {/* SCREEN 3: Set New Password */}
      {step === 3 && (
        <form onSubmit={handleResetPassword} noValidate className="form">
          <div className="step-badge">Step 3 of 3 &bull; New Password</div>
          <Alert type="error">{apiError}</Alert>
          {apiSuccess && <Alert type="success">{apiSuccess}</Alert>}

          <PasswordInput
            label="New password"
            name="password"
            autoComplete="new-password"
            value={passwordForm.password}
            onChange={handlePasswordChange}
            error={errors.password}
          />
          <PasswordStrength password={passwordForm.password} />

          <PasswordInput
            label="Confirm new password"
            name="confirmPassword"
            autoComplete="new-password"
            value={passwordForm.confirmPassword}
            onChange={handlePasswordChange}
            error={errors.confirmPassword}
          />

          <button
            type="submit"
            className="btn btn--primary"
            disabled={submitting}
            aria-busy={submitting}
          >
            {submitting ? <><Spinner /> Resetting password…</> : "Reset Password"}
          </button>
        </form>
      )}

      {/* SCREEN 4: Success State */}
      {step === 4 && (
        <div className="success-card">
          <div className="success-icon-wrap" aria-hidden="true">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="20 6 9 17 4 12" />
            </svg>
          </div>
          <Alert type="success">Your password has been reset successfully.</Alert>
          <p style={{ marginTop: 12, marginBottom: 24, fontSize: "0.95rem", color: "var(--muted)" }}>
            You can now sign in with your new password.
          </p>
          <button
            type="button"
            className="btn btn--primary"
            onClick={() => navigate("/login", { replace: true, state: { notice: "Password reset successful. Please sign in with your new password." } })}
          >
            Return to Sign In
          </button>
        </div>
      )}
    </AuthLayout>
  );
}
