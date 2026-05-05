import { createBrowserRouter, Navigate } from 'react-router-dom';
import AdminLayout from '../layouts/AdminLayout';
import LoginPage from '../pages/login/LoginPage';
import DashboardPage from '../pages/dashboard/DashboardPage';
import UsersPage from '../pages/users/UsersPage';
import UserDetailPage from '../pages/users/UserDetailPage';
import ControllersPage from '../pages/controllers/ControllersPage';
import ControllerDetailPage from '../pages/controllers/ControllerDetailPage';
import EventsPage from '../pages/events/EventsPage';
import SubscriptionsPage from '../pages/subscriptions/SubscriptionsPage';
import ParsersPage from '../pages/parsers/ParsersPage';
import AuditPage from '../pages/audit/AuditPage';
import BroadcastPage from '../pages/broadcast/BroadcastPage';
import { useAuthStore } from '../store/authStore';
import type { ReactNode } from 'react';

function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export const router = createBrowserRouter(
  [
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
        { index: true,          element: <Navigate to="/dashboard" replace /> },
        { path: 'dashboard',    element: <DashboardPage /> },
        { path: 'users',        element: <UsersPage /> },
        { path: 'users/:id',    element: <UserDetailPage /> },
        { path: 'controllers',  element: <ControllersPage /> },
        { path: 'controllers/:id', element: <ControllerDetailPage /> },
        { path: 'events',       element: <EventsPage /> },
        { path: 'subscriptions', element: <SubscriptionsPage /> },
        { path: 'parsers',      element: <ParsersPage /> },
        { path: 'audit',        element: <AuditPage /> },

        { path: 'broadcast',    element: <BroadcastPage /> },
      ],
    },
    { path: '*', element: <Navigate to="/" replace /> },
  ],
  { basename: import.meta.env.BASE_URL },
);
