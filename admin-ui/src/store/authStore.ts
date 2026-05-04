import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface AuthState {
  token: string | null;
  refreshToken: string | null;
  telegramId: number | null;
  role: string | null;
  setAuth: (
    token: string,
    refreshToken: string,
    telegramId: number,
    role: string,
  ) => void;
  clearAuth: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      refreshToken: null,
      telegramId: null,
      role: null,

      setAuth: (token, refreshToken, telegramId, role) =>
        set({ token, refreshToken, telegramId, role }),

      clearAuth: () =>
        set({ token: null, refreshToken: null, telegramId: null, role: null }),
    }),
    {
      name: 'valui-auth',
    },
  ),
);
