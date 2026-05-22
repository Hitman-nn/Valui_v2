import { useState } from 'react';
import {
  Table,
  InputNumber,
  Button,
  Space,
  Typography,
  App,
  Tooltip,
  Tag,
} from 'antd';
import { EditOutlined, CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { tokenCostsApi } from '../../api/endpoints';
import type { TokenActionCost } from '../../api/types';

const ACTION_LABELS: Record<string, string> = {
  CONTROLLER_BK_MONTHLY:          'БК-слот (ежемесячно)',
  FILTER_MONTHLY:                 'Глобальный фильтр (ежемесячно)',
  CONTROLLER_FILTER_MONTHLY:      'Фильтр на контроллере (ежемесячно)',
  NOTIFICATION_SENT:              'Отправка уведомления',
};

export default function TokenCostsPage() {
  const { notification } = App.useApp();
  const qc = useQueryClient();

  const [editingCode, setEditingCode] = useState<string | null>(null);
  const [editValue, setEditValue] = useState<number>(0);

  const { data = [], isLoading } = useQuery({
    queryKey: ['token-costs'],
    queryFn: tokenCostsApi.list,
  });

  const mutation = useMutation({
    mutationFn: ({ actionCode, costTokens }: { actionCode: string; costTokens: number }) =>
      tokenCostsApi.update(actionCode, { costTokens }),
    onSuccess: () => {
      notification.success({ message: 'Сохранено' });
      qc.invalidateQueries({ queryKey: ['token-costs'] });
      setEditingCode(null);
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  const startEdit = (record: TokenActionCost) => {
    setEditingCode(record.actionCode);
    setEditValue(record.costTokens);
  };

  const cancelEdit = () => setEditingCode(null);

  const confirmEdit = (actionCode: string) => {
    mutation.mutate({ actionCode, costTokens: editValue });
  };

  const columns = [
    {
      title: 'Код операции',
      dataIndex: 'actionCode',
      key: 'actionCode',
      width: 280,
      render: (code: string) => (
        <Typography.Text code style={{ fontSize: 12 }}>{code}</Typography.Text>
      ),
    },
    {
      title: 'Описание',
      dataIndex: 'actionCode',
      key: 'label',
      render: (code: string, record: TokenActionCost) => (
        ACTION_LABELS[code] ?? record.description ?? (
          <Typography.Text type="secondary">—</Typography.Text>
        )
      ),
    },
    {
      title: 'Стоимость, токенов',
      dataIndex: 'costTokens',
      key: 'costTokens',
      width: 200,
      render: (_: number, record: TokenActionCost) => {
        if (editingCode === record.actionCode) {
          return (
            <InputNumber
              min={0}
              value={editValue}
              onChange={(v) => setEditValue(v ?? 0)}
              onPressEnter={() => confirmEdit(record.actionCode)}
              autoFocus
              style={{ width: 100 }}
            />
          );
        }
        return (
          <Tag color={record.costTokens === 0 ? 'default' : 'blue'}>
            {record.costTokens} 🪙
          </Tag>
        );
      },
    },
    {
      title: '',
      key: 'actions',
      width: 120,
      render: (_: unknown, record: TokenActionCost) => {
        if (editingCode === record.actionCode) {
          return (
            <Space>
              <Button
                type="primary"
                size="small"
                icon={<CheckOutlined />}
                loading={mutation.isPending}
                onClick={() => confirmEdit(record.actionCode)}
              >
                Сохранить
              </Button>
              <Button size="small" icon={<CloseOutlined />} onClick={cancelEdit}>
                Отмена
              </Button>
            </Space>
          );
        }
        return (
          <Tooltip title="Изменить стоимость">
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => startEdit(record)}
            />
          </Tooltip>
        );
      },
    },
  ];

  return (
    <>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
        Стоимость операций берётся из базы данных в реальном времени — изменения вступают
        в силу немедленно, без перезапуска.
      </Typography.Paragraph>

      <Table<TokenActionCost>
        dataSource={data}
        columns={columns}
        rowKey="actionCode"
        loading={isLoading}
        pagination={false}
        size="middle"
      />
    </>
  );
}
