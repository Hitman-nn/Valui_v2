import axios, { AxiosRequestConfig } from 'axios';
import { useAuthStore } from '../store/authStore';

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor — attach Bearer token
apiClient.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token;
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response interceptor — handle 401 globally
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      useAuthStore.getState().clearAuth();
      // BASE_URL includes the basename (e.g. '/admin/'), so login path is BASE_URL + 'login'
      const loginPath = (import.meta.env.BASE_URL ?? '/').replace(/\/$/, '') + '/login';
      if (window.location.pathname !== loginPath) {
        window.location.href = loginPath;
      }
    }
    return Promise.reject(error);
  },
);

export default apiClient;

// Orval mutator signature — used by generated code
export type ApiClientConfig = AxiosRequestConfig;
export { apiClient };
