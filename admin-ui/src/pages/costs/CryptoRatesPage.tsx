import { useState } from 'react';
import {
  Table,
  InputNumber,
  Button,
  Space,
  Typography,
  App,
  Tooltip,
} from 'antd';
import { EditOutlined, CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { cryptoRatesApi } from '../../api/endpoints';
import type { ExchangeRate } from '../../api/types';
import dayjs from 'dayjs';

const { Title, Paragraph, Text } = Typography;

const CURRENCY_LABELS: Record<string, string> = {
  USDT: 'Tether (USDT)',
  TON:  'Toncoin (TON)',
  ETH:  'Ethereum (ETH)',
  BTC:  'Bitcoin (BTC)',
};

export default function CryptoRatesPage() {
  const { notification } = App.useApp();
  const qc = useQueryClient();

  const [editingCurrency, setEditingCurrency] = useState<string | null>(null);
  const [editValue, setEditValue] = useState<number>(0);

  const { data = [], isLoading } = useQuery({
    queryKey: ['crypto-rates'],
    queryFn: cryptoRatesApi.list,
  });

  const mutation = useMutation({
    mutationFn: ({ currency, tokensPerUnit }: { currency: string; tokensPerUnit: number }) =>
      cryptoRatesApi.update(currency, { tokensPerUnit }),
    onSuccess: () => {
      notification.success({ message: 'Курс сохранён' });
      qc.invalidateQueries({ queryKey: ['crypto-rates'] });
      setEditingCurrency(null);
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  const columns = [
    {
      title: 'Валюта',
      dataIndex: 'currency',
      key: 'currency',
      width: 100,
      render: (currency: string) => <Text strong>{currency}</Text>,
    },
    {
      title: 'Название',
      dataIndex: 'currency',
      key: 'label',
      render: (currency: string) => CURRENCY_LABELS[currency] ?? currency,
    },
    {
      title: 'Токенов за единицу',
      dataIndex: 'tokensPerUnit',
      key: 'tokensPerUnit',
      width: 240,
      render: (_: number, record: ExchangeRate) => {
        if (editingCurrency === record.currency) {
          return (
            <InputNumber
              min={0.00000001}
              step={1}
              precision={8}
              value={editValue}
              onChange={(v) => setEditValue(v ?? 0)}
              style={{ width: 160 }}
              autoFocus
            />
          );
        }
        return <Text>{record.tokensPerUnit}</Text>;
      },
    },
    {
      title: 'Обновлено',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 160,
      render: (v: string) => dayjs(v).format('DD.MM.YYYY HH:mm'),
    },
    {
      title: '',
      key: 'actions',
      width: 100,
      render: (_: unknown, record: ExchangeRate) => {
        if (editingCurrency === record.currency) {
          return (
            <Space>
              <Tooltip title="Сохранить">
                <Button
                  icon={<CheckOutlined />}
                  type="primary"
                  size="small"
                  loading={mutation.isPending}
                  onClick={() => mutation.mutate({ currency: record.currency, tokensPerUnit: editValue })}
                />
              </Tooltip>
              <Tooltip title="Отмена">
                <Button
                  icon={<CloseOutlined />}
                  size="small"
                  onClick={() => setEditingCurrency(null)}
                />
              </Tooltip>
            </Space>
          );
        }
        return (
          <Tooltip title="Изменить">
            <Button
              icon={<EditOutlined />}
              size="small"
              onClick={() => {
                setEditingCurrency(record.currency);
                setEditValue(record.tokensPerUnit);
              }}
            />
          </Tooltip>
        );
      },
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Title level={3}>Курсы обмена криптовалют</Title>
      <Paragraph type="secondary">
        Задайте, сколько токенов Valui получает пользователь за 1 единицу каждой криптовалюты.
      </Paragraph>
      <Table
        rowKey="currency"
        columns={columns}
        dataSource={data}
        loading={isLoading}
        pagination={false}
        size="middle"
        style={{ maxWidth: 700 }}
      />
    </div>
  );
}
