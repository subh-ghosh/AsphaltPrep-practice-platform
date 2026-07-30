import React, { createContext, useContext, useState, useEffect, useCallback } from "react";
import api from "@/api";
import { useAuth } from "./AuthContext";

const NotificationContext = createContext();

export function useNotifications() {
  return useContext(NotificationContext);
}

export function NotificationProvider({ children }) {
  const { user } = useAuth();
  const [notifications, setNotifications] = useState([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // 1. Fetch Notifications (Single Source of Truth)
  const fetchNotifications = useCallback(async (isSilent = false) => {
    const token = localStorage.getItem("token");
    if (!token) return;

    if (!isSilent) setLoading(true);

    try {
      const res = await api.get("/notifications");

      setNotifications(res.data || []);

      // Calculate unread count locally
      const unread = (res.data || []).filter(n => !n.readFlag).length;
      setUnreadCount(unread);
      setError(null);
    } catch (err) {
      console.error("Context: Failed to fetch notifications", err);
      if (err.response && err.response.status !== 404) {
        setError("Could not load notifications");
      }
    } finally {
      if (!isSilent) setLoading(false);
    }
  }, []);

  // 2. Mark Read (Optimistic Update)
  const markRead = useCallback(async (id) => {
    setNotifications(prev =>
      prev.map(n => n.id === id ? { ...n, readFlag: true } : n)
    );
    setUnreadCount(prev => Math.max(0, prev - 1));

    try {
      await api.patch(`/notifications/${id}/read`);
    } catch (err) {
      console.error("Context: Failed to mark read", err);
      fetchNotifications(true);
    }
  }, [fetchNotifications]);

  // 3. Mark ALL Read (Optimistic Update)
  const markAllAsRead = useCallback(async () => {
    setNotifications(prev => prev.map(n => ({ ...n, readFlag: true })));
    setUnreadCount(0);

    try {
      await api.patch("/notifications/read-all");
    } catch (err) {
      console.error("Context: Failed to mark all read", err);
      fetchNotifications(true);
    }
  }, [fetchNotifications]);

  // Initial Fetch & Polling
  useEffect(() => {
    if (user) {
      fetchNotifications();
      // Poll every 30 seconds for real-time notifications
      const interval = setInterval(() => {
        if (!document.hidden) {
          fetchNotifications(true);
        }
      }, 30000);

      const onVisibility = () => {
        if (!document.hidden) {
          fetchNotifications(true);
        }
      };
      document.addEventListener("visibilitychange", onVisibility);

      return () => {
        clearInterval(interval);
        document.removeEventListener("visibilitychange", onVisibility);
      };
    } else {
      setNotifications([]);
      setUnreadCount(0);
    }
  }, [user, fetchNotifications]);

  const value = {
    notifications,
    unreadCount,
    loading,
    error,
    markRead,
    markAllAsRead,
    refresh: () => fetchNotifications(false)
  };

  return (
    <NotificationContext.Provider value={value}>
      {children}
    </NotificationContext.Provider>
  );
}
