import { createBrowserRouter, Navigate } from 'react-router-dom';
import AdminLayout from '../layouts/AdminLayout';
import LoginPage from '../pages/login/LoginPage';
import UsersPage from '../pages/users/UsersPage';
import UserDetailPage from '../pages/users/UserDetailPage';
import SubscriptionsPage from '../pages/subscriptions/SubscriptionsPage';
import ParsersPage from '../pages/parsers/ParsersPage';
import SystemPage from '../pages/system/SystemPage';
import BroadcastPage from '../pages/broadcast/BroadcastPage';
import { useAuthStore } from '../store/authStore';
import type { ReactNode } from 'react';

function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AdminLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/system" replace /> },
      { path: 'users', element: <UsersPage /> },
      { path: 'users/:id', element: <UserDetailPage /> },
      { path: 'subscriptions', element: <SubscriptionsPage /> },
      { path: 'parsers', element: <ParsersPage /> },
      { path: 'system', element: <SystemPage /> },
      { path: 'broadcast', element: <BroadcastPage /> },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
]);
