import { Card, Statistic } from 'antd';
import type { ReactNode } from 'react';

interface StatCardProps {
  title: string;
  value: number | string;
  prefix?: ReactNode;
  suffix?: string;
  loading?: boolean;
  color?: string;
}

export default function StatCard({
  title,
  value,
  prefix,
  suffix,
  loading,
  color,
}: StatCardProps) {
  return (
    <Card loading={loading} style={{ flex: 1, minWidth: 150 }}>
      <Statistic
        title={title}
        value={value}
        prefix={prefix}
        suffix={suffix}
        valueStyle={color ? { color } : undefined}
      />
    </Card>
  );
}
