import { createContext, useContext, useState, useEffect } from "react";
import axios from "axios";
import { API_BASE_URL } from "@/config";

const AuthContext = createContext();

export function useAuth() {
  return useContext(AuthContext);
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  // Use dynamic URL from config
  const API_URL = `${API_BASE_URL}/students`;

  // 1. Check for token on startup (Auto-Login with fresh data)
  useEffect(() => {
    const checkUser = async () => {
      const token = localStorage.getItem("token");
      if (token) {
        try {
          // Optimistically restore from local storage
          const savedUser = localStorage.getItem("user");
          if (savedUser) {
            setUser(JSON.parse(savedUser));
          }

          // Then fetch fresh data from backend (Streak, XP, etc.)
          const res = await axios.get(`${API_URL}/profile`, {
            headers: { Authorization: `Bearer ${token}` }
          });

          if (res.data) {
            const freshUser = res.data;
            setUser(freshUser);
            localStorage.setItem("user", JSON.stringify(freshUser));
          }

        } catch (error) {
          console.error("Token invalid or profile fetch failed", error);
          // If 401, clear session. If network error, keep local data.
          if (error.response?.status === 401) {
            localStorage.removeItem("token");
            localStorage.removeItem("user");
            setUser(null);
          }
        }
      }
      setLoading(false);
    };
    checkUser();
  }, []);

  // 2. Login Function
  const login = async (email, password) => {
    try {
      const response = await axios.post(`${API_URL}/login`, { email, password });

      const token = response.data.token ||
        response.data.jwt ||
        response.data.accessToken ||
        (typeof response.data === 'string' ? response.data : null);

      if (token) {
        localStorage.setItem("token", token);

        const userData = response.data.student ||
          response.data.user ||
          { email: email, firstName: "Student" };

        localStorage.setItem("user", JSON.stringify(userData));
        setUser(userData);
        return { success: true };
      } else {
        return { success: false, message: "Login failed: No token received." };
      }
    } catch (error) {
      console.error("Login Error:", error);
      return {
        success: false,
        message: error.response?.data?.message || "Invalid credentials"
      };
    }
  };

  // 3. GitHub Login Function
  const loginWithGitHub = async (code) => {
    try {
      const response = await axios.post(`${API_URL}/oauth/github`, { code });

      if (response.data.status === "LOGIN_SUCCESS") {
        localStorage.setItem("token", response.data.token);

        const userData = response.data.student;
        localStorage.setItem("user", JSON.stringify(userData));
        setUser(userData);

        return { success: true, status: "LOGIN_SUCCESS" };
      }

      return { success: false, message: response.data?.message || "GitHub authentication failed" };

    } catch (error) {
      console.error("GitHub Auth Error:", error);
      return { success: false, message: error.response?.data?.message || "GitHub authentication failed" };
    }
  };

  // 4. Logout Function
  const logout = () => {
    localStorage.removeItem("token");
    localStorage.removeItem("user");
    setUser(null);
  };

  // --- 👇 NEW FUNCTION: Update User State Manually 👇 ---
  // This updates the local UI immediately after a successful API save
  const updateUser = (updatedData) => {
    setUser((prevUser) => {
      // Merge the old user data with the new updates
      const newUser = { ...prevUser, ...updatedData };

      // Save to localStorage so it persists on refresh
      localStorage.setItem("user", JSON.stringify(newUser));

      // If the backend sent a new token (rare but possible), update that too
      if (updatedData.token) {
        localStorage.setItem("token", updatedData.token);
      }

      return newUser;
    });
  };

  // --- 👇 decrementFreeActions for Free Tier 👇 ---
  // This updates the limit counter locally so the user sees the change instantly
  const decrementFreeActions = () => {
    setUser((prev) => {
      if (!prev) return null;
      const newUser = { ...prev, freeActionsUsed: (prev.freeActionsUsed || 0) + 1 };
      localStorage.setItem("user", JSON.stringify(newUser));
      return newUser;
    });
  };

  const value = {
    user,
    loading,
    login,
    loginWithGitHub,
    logout,
    updateUser, // 👈 Exported for Profile page
    decrementFreeActions // 👈 Exported for Practice page
  };

  return (
    <AuthContext.Provider value={value}>
      {!loading && children}
    </AuthContext.Provider>
  );
}
