import React from "react";
import { Button, Typography } from "@material-tailwind/react";
import { MagneticButton } from "@/components/ui/PremiumEffects";

export class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, errorInfo) {
    console.error("Uncaught React rendering error:", error, errorInfo);
  }

  handleReload = () => {
    this.setState({ hasError: false, error: null });
    window.location.href = "/";
  };

  render() {
    if (this.state.hasError) {
      return (
        <div className="min-h-screen bg-[#050505] text-white flex flex-col items-center justify-center p-6 text-center font-sans selection:bg-blue-500/30">
          <div className="max-w-md w-full backdrop-blur-2xl bg-[#0a0a0c]/90 border border-white/10 rounded-3xl p-8 shadow-2xl flex flex-col items-center gap-6">
            <div className="w-16 h-16 rounded-2xl bg-red-500/10 border border-red-500/20 flex items-center justify-center text-red-400 text-3xl">
              ⚠️
            </div>

            <div>
              <Typography variant="h3" className="font-bold text-2xl mb-2 text-white">
                Something Went Wrong
              </Typography>
              <Typography variant="paragraph" className="text-slate-400 text-sm">
                An unexpected UI rendering glitch occurred. Don't worry, your data and session remain secure.
              </Typography>
            </div>

            <MagneticButton className="w-full rounded-full">
              <Button
                onClick={this.handleReload}
                className="w-full bg-white text-black hover:scale-[1.02] shadow-none hover:shadow-lg transition-all rounded-full py-3.5 font-medium cursor-pointer"
              >
                Reload Asphalt Prep
              </Button>
            </MagneticButton>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}

export default ErrorBoundary;
