import {
  Input,
  Button,
  Typography,
  Alert,
  Spinner,
} from "@material-tailwind/react";
import { Link, useNavigate, useLocation } from "react-router-dom";
import { useState, useEffect } from "react";
import { useAuth } from "@/context/AuthContext";
import { motion } from "framer-motion";
import { MagneticButton, CustomCursor } from "@/components/ui/PremiumEffects";
import { FlashlightBackground } from "@/components/ui/FlashlightBackground";
import Spline from "@splinetool/react-spline";

export function SignIn() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [githubSubmitting, setGithubSubmitting] = useState(false);

  const { login, loginWithGitHub } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = location.state?.from?.pathname || "/dashboard/home";

  useEffect(() => {
    if (location.state?.success) {
      setSuccess(location.state.message);
      window.history.replaceState({}, document.title);
    }
  }, [location.state]);

  // Handle GitHub OAuth Redirect Code Callback
  useEffect(() => {
    const queryParams = new URLSearchParams(location.search);
    const code = queryParams.get("code");
    if (code) {
      setGithubSubmitting(true);
      loginWithGitHub(code).then((result) => {
        setGithubSubmitting(false);
        if (result.success) {
          navigate(from, { replace: true });
        } else {
          setError(result.message || "GitHub authentication failed.");
        }
      });
    }
  }, [location.search]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError("");
    setSuccess("");
    if (!email.trim() || !password.trim()) {
      setError("Please enter both email and password.");
      return;
    }

    setSubmitting(true);
    const result = await login(email, password);
    setSubmitting(false);

    if (result?.success) {
      navigate(from, { replace: true });
    } else {
      setError(result?.message || "Invalid email or password.");
    }
  };

  const handleGitHubAuth = () => {
    setGithubSubmitting(true);
    const clientId = import.meta.env.VITE_GITHUB_CLIENT_ID;
    const redirectUri = window.location.origin + window.location.pathname;
    window.location.href = `https://github.com/login/oauth/authorize?client_id=${clientId}&scope=user:email&redirect_uri=${encodeURIComponent(redirectUri)}`;
  };

  return (
    <div className="relative w-full overflow-hidden bg-[#050505] min-h-[calc(100vh+6rem)] flex flex-col font-sans selection:bg-blue-500/30 -mt-24">
      <CustomCursor />

      <FlashlightBackground className="flex-grow">
        <section className="relative min-h-screen flex flex-col md:flex-row md:items-center md:justify-center gap-6 px-4 md:px-10 overflow-hidden pt-36 md:pt-32 pb-24 md:pb-0">

          {/* === Autofill Fix Style Block === */}
          <style>{`
        input:-webkit-autofill,
        input:-webkit-autofill:hover, 
        input:-webkit-autofill:focus, 
        input:-webkit-autofill:active {
          -webkit-box-shadow: 0 0 0 1000px #18181b inset !important;
          -webkit-text-fill-color: #ffffff !important;
          transition: background-color 5000s ease-in-out 0s;
        }
      `}</style>


          {/* Left Form */}
          <div className="w-full lg:w-3/5 flex flex-col items-center justify-center py-6 lg:py-0 z-10">
            <motion.div
              initial={{ opacity: 0, y: -20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5 }}
              className="text-center mb-6"
            >
              <Typography
                variant="h2"
                className="font-bold mb-2 text-3xl md:text-4xl text-white tracking-tight"
              >
                Sign In
              </Typography>
              <Typography
                variant="paragraph"
                className="text-lg font-medium text-slate-400 opacity-90"
              >
                Enter your email and password to sign in.
              </Typography>
            </motion.div>

            <motion.form
              initial={{ opacity: 0, scale: 0.95 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ duration: 0.5, delay: 0.1 }}
              onSubmit={handleSubmit}
              className="w-full max-w-[420px] flex flex-col gap-4 backdrop-blur-xl bg-[#0a0a0c]/80 border border-white/5 rounded-2xl p-6 shadow-2xl"
            >
              {success && <Alert color="green">{success}</Alert>}
              {error && <Alert color="red">{error}</Alert>}

              <div className="flex flex-col gap-4">
                <Input
                  size="lg"
                  type="email"
                  label="Your email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  color="white"
                  autoComplete="email"
                  className="!bg-transparent focus:!border-white/20 !text-white"
                  labelProps={{
                    className: "text-slate-400 peer-placeholder-shown:text-slate-400 peer-focus:text-white"
                  }}
                />

                <Input
                  size="lg"
                  type="password"
                  label="Password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  color="white"
                  autoComplete="current-password"
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
                  className="bg-white text-black hover:scale-[1.02] shadow-none hover:shadow-lg transition-all w-full rounded-full"
                  fullWidth
                  disabled={submitting || githubSubmitting}
                >
                  {submitting ? "Signing in..." : "Sign In"}
                </Button>
              </MagneticButton>

              <div className="relative flex py-2 items-center">
                <div className="flex-grow border-t border-white/10"></div>
                <span className="flex-shrink mx-4 text-slate-500 text-sm">OR</span>
                <div className="flex-grow border-t border-white/10"></div>
              </div>

              <div className="flex justify-center w-full">
                <Button
                  type="button"
                  onClick={handleGitHubAuth}
                  disabled={submitting || githubSubmitting}
                  className="w-full bg-[#181717] hover:bg-[#2b2a2a] text-white flex items-center justify-center gap-3 rounded-full py-3 border border-white/15 transition-all shadow-md hover:shadow-xl font-medium tracking-wide text-sm"
                >
                  <svg className="w-5 h-5 fill-current shrink-0" viewBox="0 0 24 24">
                    <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"/>
                  </svg>
                  {githubSubmitting ? "Connecting to GitHub..." : "Sign in with GitHub"}
                </Button>
              </div>

              <Typography
                variant="paragraph"
                className="text-center font-medium mt-2 text-slate-400"
              >
                Not registered?
                <Link
                  to="/auth/sign-up"
                  className="text-blue-400 hover:text-blue-300 ml-1 transition-colors"
                >
                  Create account
                </Link>
              </Typography>
            </motion.form>
          </div>

          {/* Right Image */}
          <motion.div
            initial={{ opacity: 0, x: 50 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.8 }}
            className="hidden lg:flex w-2/5 justify-center"
          >
            <div className="relative w-full h-[80vh] rounded-[2rem] overflow-hidden shadow-2xl border border-white/5 bg-[#050505]">
              <Spline
                scene="https://prod.spline.design/V74KBkG7UbSNupbi/scene.splinecode"
                className="scale-[1.2] transform-gpu origin-center"
              />
              <div className="absolute inset-0 bg-gradient-to-t from-[#050505] to-transparent/10 pointer-events-none" />
            </div>
          </motion.div>
        </section>
      </FlashlightBackground>
    </div>
  );
}

export default SignIn;