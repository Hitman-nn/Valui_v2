import { useState } from 'react';
import {
  Table,
  Select,
  Space,
  Tag,
  Typography,
  TablePaginationConfig,
  Tooltip,
} from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { auditApi } from '../../api/endpoints';
import type { AuditLog } from '../../api/types';

const ACTION_OPTIONS = [
  { label: 'Все действия', value: '' },
  { label: 'USER_BAN', value: 'USER_BAN' },
  { label: 'USER_UNBAN', value: 'USER_UNBAN' },
  { label: 'USER_ROLE_CHANGE', value: 'USER_ROLE_CHANGE' },
  { label: 'USER_DELETE', value: 'USER_DELETE' },
  { label: 'CONTROLLER_DELETE', value: 'CONTROLLER_DELETE' },
  { label: 'SUBSCRIPTION_CHANGE', value: 'SUBSCRIPTION_CHANGE' },
  { label: 'BROADCAST_SENT', value: 'BROADCAST_SENT' },
  { label: 'EVENT_DELETE', value: 'EVENT_DELETE' },
  { label: 'PARSER_POLL_TRIGGERED', value: 'PARSER_POLL_TRIGGERED' },
];

function actionColor(action: string): string {
  if (action.includes('DELETE') || action.includes('BAN')) return 'error';
  if (action.includes('CHANGE') || action.includes('EDIT')) return 'warning';
  if (action.includes('SENT') || action.includes('TRIGGERED')) return 'processing';
  return 'default';
}

export default function AuditPage() {
  const [page, setPage]     = useState(0);
  const [action, setAction] = useState('');

  const { data, isLoading } = useQuery({
    queryKey: ['audit-log', page, action],
    queryFn: () => auditApi.list({ page, size: 25, ...(action ? { action } : {}) }),
  });

  const handleTableChange = (p: TablePaginationConfig) => setPage((p.current ?? 1) - 1);

  const columns = [
    {
      title: 'Время',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 150,
      render: (v: string) => dayjs(v).format('DD.MM.YY HH:mm:ss'),
    },
    {
      title: 'Действие',
      dataIndex: 'action',
      key: 'action',
      width: 220,
      render: (v: string) => <Tag color={actionColor(v)}>{v}</Tag>,
    },
    {
      title: 'Объект',
      key: 'entity',
      width: 160,
      render: (_: unknown, r: AuditLog) =>
        r.entityType ? (
          <Space size={4}>
            <Tag>{r.entityType}</Tag>
            {r.entityId && (
              <Tooltip title={r.entityId}>
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {r.entityId.substring(0, 8)}…
                </Typography.Text>
              </Tooltip>
            )}
          </Space>
        ) : <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: 'Детали',
      dataIndex: 'details',
      key: 'details',
      render: (v: string | null) => {
        if (!v) return <Typography.Text type="secondary">—</Typography.Text>;
        const trimmed = v.length > 80 ? v.substring(0, 80) + '…' : v;
        return <Tooltip title={<pre style={{ fontSize: 11, maxWidth: 400 }}>{v}</pre>}>
          <Typography.Text style={{ fontSize: 12 }}>{trimmed}</Typography.Text>
        </Tooltip>;
      },
    },
    {
      title: 'IP',
      dataIndex: 'ipAddress',
      key: 'ip',
      width: 130,
      render: (v: string | null) => v ?? <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: 'User ID',
      dataIndex: 'userId',
      key: 'userId',
      width: 120,
      render: (v: string | null) => v ? (
        <Tooltip title={v}>
          <Typography.Text type="secondary" style={{ fontSize: 11 }}>{v.substring(0, 8)}…</Typography.Text>
        </Tooltip>
      ) : <Typography.Text type="secondary">—</Typography.Text>,
    },
  ];

  return (
    <>
      <Space style={{ marginBottom: 16 }}>
        <Select
          value={action}
          onChange={(v) => { setAction(v); setPage(0); }}
          style={{ width: 240 }}
          options={ACTION_OPTIONS}
        />
      </Space>

      <Table<AuditLog>
        dataSource={data?.content ?? []}
        columns={columns}
        rowKey="id"
        loading={isLoading}
        pagination={{
          current: page + 1,
          pageSize: 25,
          total: data?.totalElements ?? 0,
          showSizeChanger: false,
          showTotal: (t) => `Всего ${t} записей`,
        }}
        onChange={handleTableChange}
        size="middle"
      />
    </>
  );
}
