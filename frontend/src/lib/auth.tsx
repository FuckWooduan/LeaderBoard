"use client";

/** 站点账号登录态（Context）：/api/auth/me 同步 + localStorage 缓存 token。 */

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { api, post } from "./api";

export interface AuthUser {
  username: string;
  role: "USER" | "ADMIN";
  emailVerified: boolean;
  email: string;
  /** 注册时刻（epoch ms，0=未知） */
  createdAt?: number;
  /** 上次登录时刻（epoch ms，0=未知） */
  lastLoginAt?: number;
}

interface AuthState {
  user: AuthUser | null;
  /** me 接口是否已返回（避免登录态闪烁）。 */
  ready: boolean;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
}

const AuthCtx = createContext<AuthState>({
  user: null,
  ready: false,
  refresh: async () => {},
  logout: async () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [ready, setReady] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const d = await api("/api/auth/me");
      setUser(
        d.ok
          ? {
              username: d.username,
              role: d.role,
              emailVerified: Boolean(d.emailVerified),
              email: d.email || "",
              createdAt: d.createdAt || 0,
              lastLoginAt: d.lastLoginAt || 0,
            }
          : null
      );
      if (!d.ok) localStorage.removeItem("user_token");
    } catch {
      setUser(null);
    } finally {
      setReady(true);
    }
  }, []);

  const logout = useCallback(async () => {
    try {
      await post("/api/auth/logout");
    } catch {
      /* 即使后端失败也清本地 */
    }
    localStorage.removeItem("user_token");
    setUser(null);
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return <AuthCtx.Provider value={{ user, ready, refresh, logout }}>{children}</AuthCtx.Provider>;
}

export function useAuth() {
  return useContext(AuthCtx);
}

/** 登录/注册成功后的统一处理：存 token 并刷新上下文。 */
export function storeLogin(token: string) {
  localStorage.setItem("user_token", token);
}
