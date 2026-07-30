import {
  Input,
  Button,
  Typography,
  Alert,
} from "@material-tailwind/react";
import { Link, useNavigate, useLocation } from "react-router-dom";
import { useState, useEffect } from "react";
import api from "@/api";
import { useAuth } from "@/context/AuthContext";
import { motion } from "framer-motion";
import { MagneticButton, CustomCursor } from "@/components/ui/PremiumEffects";
import { FlashlightBackground } from "@/components/ui/FlashlightBackground";
import Spline from "@splinetool/react-spline";

export function SignUp() {
  const location = useLocation();

  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState(null);
  const [githubSubmitting, setGithubSubmitting] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const [githubData, setGithubData] = useState(location.state?.githubData || null);
  const [isGithubRegister, setIsGithubRegister] = useState(false);

  const navigate = useNavigate();
  const { login } = useAuth();
  const from = location.state?.from?.pathname || "/dashboard/home";

  useEffect(() => {
    let pendingGithubData = location.state?.githubData;
    if (!pendingGithubData) {
      try {
        pendingGithubData = JSON.parse(sessionStorage.getItem("pendingGithubRegistration") || "null");
      } catch (_) {
        pendingGithubData = null;
      }
    }

    if (pendingGithubData) {
      setFirstName(pendingGithubData.firstName || "");
      setLastName(pendingGithubData.lastName || "");
      setEmail(pendingGithubData.email || "");
      setGithubData(pendingGithubData);
      setIsGithubRegister(true);
      sessionStorage.removeItem("pendingGithubRegistration");
    }
  }, [location.state]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    if (!firstName || !lastName || !email || !password) {
      setError("All fields are required.");
      return;
    }
    if (password.length < 6) {
      setError("Password must be at least 6 characters long.");
      return;
    }

    setSubmitting(true);
    try {
      await api.post("/students/register", {
        firstName,
        lastName,
        email,
        password,
        avatarUrl: githubData?.avatarUrl || "",
        githubUrl: githubData?.githubUrl || ""
      });

      // Auto sign-in after setting password
      const loginResult = await login(email, password);
      if (loginResult?.success) {
        navigate(from, { replace: true });
        return;
      }

      navigate("/auth/sign-in", {
        state: {
          success: true,
          message: "Registration successful. Sign in to continue.",
        },
      });
    } catch (err) {
      if (err.response && err.response.status === 409) {
        setError("An account with this email already exists.");
      } else {
        console.error("Registration failed:", err);
        setError("Registration failed. Please try again.");
      }
    }
    setSubmitting(false);
  };

  return (
    <div className="relative w-full overflow-hidden bg-[#050505] min-h-[calc(100vh+6rem)] flex flex-col font-sans selection:bg-blue-500/30 -mt-24">
      <CustomCursor />

      <FlashlightBackground className="flex-grow">
        <div className="min-h-screen flex items-center justify-center p-4 pt-32 pb-16">
          <div className="container mx-auto flex flex-col lg:flex-row items-center justify-between gap-12 max-w-6xl">
            {/* Left side text & form */}
            <motion.div
              initial={{ opacity: 0, x: -30 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ duration: 0.6 }}
              className="w-full lg:w-1/2 flex flex-col items-center lg:items-start text-center lg:text-left z-10"
            >
              <Typography
                variant="h2"
                className="font-bold mb-2 text-3xl md:text-4xl text-white tracking-tight"
              >
                {isGithubRegister ? "Complete Your GitHub Registration" : "Join Us Today"}
              </Typography>
              <Typography
                variant="paragraph"
                className="text-lg font-medium text-slate-400 opacity-90 mb-6"
              >
                {isGithubRegister
                  ? "Please set a password for your account to finish."
                  : "Enter your details to register."}
              </Typography>

              <motion.form
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
                transition={{ duration: 0.5, delay: 0.1 }}
                className="w-full max-w-[420px] flex flex-col gap-4 backdrop-blur-xl bg-[#0a0a0c]/80 border border-white/5 rounded-2xl p-6 shadow-2xl"
                onSubmit={handleSubmit}
              >
                {error && <Alert color="red">{error}</Alert>}

                {isGithubRegister && (
                  <Alert color="amber" className="text-amber-900 font-medium">
                    Please set a password to complete registration for your GitHub account ({email}).
                  </Alert>
                )}

                <div className="flex flex-col gap-3">
                  <Input
                    size="lg"
                    label="First Name"
                    value={firstName}
                    onChange={(e) => setFirstName(e.target.value)}
                    color="white"
                    disabled={isGithubRegister}
                    className="!bg-transparent focus:!border-white/20 !text-white"
                    labelProps={{
                      className: "text-slate-400 peer-placeholder-shown:text-slate-400 peer-focus:text-white"
                    }}
                  />

                  <Input
                    size="lg"
                    label="Last Name"
                    value={lastName}
                    onChange={(e) => setLastName(e.target.value)}
                    color="white"
                    disabled={isGithubRegister}
                    className="!bg-transparent focus:!border-white/20 !text-white"
                    labelProps={{
                      className: "text-slate-400 peer-placeholder-shown:text-slate-400 peer-focus:text-white"
                    }}
                  />

                  <Input
                    size="lg"
                    type="email"
                    label="Your email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    color="white"
                    disabled={isGithubRegister}
                    autoComplete="email"
                    className="!bg-transparent focus:!border-white/20 !text-white"
                    labelProps={{
                      className: "text-slate-400 peer-placeholder-shown:text-slate-400 peer-focus:text-white"
                    }}
                  />

                  <Input
                    type="password"
                    size="lg"
                    label="Set Account Password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    color="white"
                    autoComplete="new-password"
                    className="!bg-transparent focus:!border-white/20 !text-white"
                    labelProps={{
                      className: "text-slate-400 peer-placeholder-shown:text-slate-400 peer-focus:text-white"
                    }}
                  />
                </div>

                <MagneticButton
                  onClick={() => { }}
                  className="mt-3 w-full rounded-full"
                >
                  <Button
                    type="submit"
                    className="bg-white text-black hover:scale-[1.02] shadow-none hover:shadow-lg transition-all w-full rounded-full cursor-pointer"
                    fullWidth
                    disabled={submitting || githubSubmitting}
                  >
                    {isGithubRegister
                      ? submitting
                        ? "Completing Registration..."
                        : "Complete Registration"
                      : submitting
                        ? "Registering..."
                        : "Register Now"}
                  </Button>
                </MagneticButton>

                {!isGithubRegister && (
                  <>
                    <div className="relative flex py-2 items-center">
                      <div className="flex-grow border-t border-white/10"></div>
                      <span className="flex-shrink mx-4 text-slate-500 text-sm">OR</span>
                      <div className="flex-grow border-t border-white/10"></div>
                    </div>

                    <MagneticButton className="w-full rounded-full">
                      <Button
                        type="button"
                        onClick={() => {
                          setGithubSubmitting(true);
                          const clientId = import.meta.env.VITE_GITHUB_CLIENT_ID;
                          const redirectUri = window.location.origin + "/auth/sign-in";
                          window.location.href = `https://github.com/login/oauth/authorize?client_id=${clientId}&scope=user:email&redirect_uri=${encodeURIComponent(redirectUri)}`;
                        }}
                        disabled={githubSubmitting}
                        className="w-full bg-[#181717] hover:bg-[#24292e] hover:scale-[1.02] text-white flex items-center justify-center gap-3 rounded-full py-3.5 border border-white/15 transition-all shadow-md hover:shadow-xl font-medium tracking-wide text-sm cursor-pointer"
                      >
                        <svg className="w-5 h-5 fill-current shrink-0" viewBox="0 0 24 24">
                          <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z" />
                        </svg>
                        {githubSubmitting ? "Connecting to GitHub..." : "Sign up with GitHub"}
                      </Button>
                    </MagneticButton>
                  </>
                )}

                <Typography variant="small" className="mt-4 text-center font-normal text-slate-400">
                  Already have an account?{" "}
                  <Link to="/auth/sign-in" className="font-bold text-white hover:underline transition-all">
                    Sign In
                  </Link>
                </Typography>
              </motion.form>
            </motion.div>

            {/* Right side spline / visual */}
            <motion.div
              initial={{ opacity: 0, scale: 0.8 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ duration: 0.8, delay: 0.2 }}
              className="w-full lg:w-1/2 h-[400px] lg:h-[500px] relative hidden md:block"
            >
              <div className="absolute inset-0 bg-gradient-to-tr from-blue-500/10 to-purple-500/10 rounded-3xl blur-3xl pointer-events-none" />
              <Spline scene="https://prod.spline.design/kZ66C8E05YtWAHkh/scene.splinecode" />
            </motion.div>
          </div>
        </div>
      </FlashlightBackground>
    </div>
  );
}

export default SignUp;
