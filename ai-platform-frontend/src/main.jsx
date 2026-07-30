import './styles/tailwind.css';
import './scrollbar.css';
import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { BrowserRouter } from "react-router-dom";
import { ThemeProvider } from "@material-tailwind/react";
import { MaterialTailwindControllerProvider } from "@/context";
import { AuthProvider } from "@/context/AuthContext";
import { NotificationProvider } from "@/context/NotificationContext.jsx";
import { ThemeProvider as AppThemeProvider } from "@/context/ThemeContext.jsx";
import { PaywallProvider } from "@/context/PaywallContext.jsx";
import axios from "axios";

// FIXED SECURITY INTERCEPTOR
axios.interceptors.request.use(
  (config) => {
    // DO NOT attach token to auth endpoints
    if (
      config.url?.includes("/login") ||
      config.url?.includes("/register") ||
      config.url?.includes("/oauth")
    ) {
      return config;
    }

    const token = localStorage.getItem("token");

    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }

    return config;
  },
  (error) => Promise.reject(error)
);

// APP RENDER
ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <BrowserRouter>
      <ThemeProvider>
        <MaterialTailwindControllerProvider>
          <AppThemeProvider>
            <AuthProvider>
              <NotificationProvider>
                <PaywallProvider>
                  <App />
                </PaywallProvider>
              </NotificationProvider>
            </AuthProvider>
          </AppThemeProvider>
        </MaterialTailwindControllerProvider>
      </ThemeProvider>
    </BrowserRouter>
  </React.StrictMode>
);
