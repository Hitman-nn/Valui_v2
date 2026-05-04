import { Tag } from 'antd';

type UserStatus = 'ACTIVE' | 'BANNED' | 'PENDING';
type UserRole = 'USER' | 'ADMIN';
type Indicator = 'green' | 'yellow' | 'red';

interface StatusBadgeProps {
  status: UserStatus;
}

interface RoleBadgeProps {
  role: UserRole;
}

interface IndicatorBadgeProps {
  indicator: Indicator;
  label?: string;
}

export function StatusBadge({ status }: StatusBadgeProps) {
  const colorMap: Record<UserStatus, string> = {
    ACTIVE: 'success',
    BANNED: 'error',
    PENDING: 'warning',
  };
  return <Tag color={colorMap[status]}>{status}</Tag>;
}

export function RoleBadge({ role }: RoleBadgeProps) {
  return <Tag color={role === 'ADMIN' ? 'purple' : 'default'}>{role}</Tag>;
}

export function IndicatorBadge({ indicator, label }: IndicatorBadgeProps) {
  const colorMap: Record<Indicator, string> = {
    green: 'success',
    yellow: 'warning',
    red: 'error',
  };
  return <Tag color={colorMap[indicator]}>{label ?? indicator.toUpperCase()}</Tag>;
}
