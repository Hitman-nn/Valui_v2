import { useState } from 'react';
import {
  Card,
  Form,
  Input,
  Select,
  Button,
  Row,
  Col,
  Typography,
  Modal,
  Space,
  Tag,
  Result,
  App,
} from 'antd';
import { SendOutlined, EyeOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { broadcastApi } from '../../api/endpoints';
import type { BroadcastRequest } from '../../api/types';

const STATUS_OPTIONS = [
  { label: 'Все пользователи', value: 'ALL' },
  { label: 'Активные (ACTIVE)', value: 'ACTIVE' },
  { label: 'Заблокированные (BANNED)', value: 'BANNED' },
];

function statusTagColor(status: string | null): string {
  switch (status) {
    case 'ACTIVE': return 'success';
    case 'BANNED': return 'error';
    default: return 'processing';
  }
}

function renderTelegramPreview(text: string): string {
  return text
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.*?)\*/g, '<em>$1</em>')
    .replace(/`(.*?)`/g, '<code style="background:#333;padding:1px 4px;border-radius:3px">$1</code>')
    .replace(/\n/g, '<br/>');
}

interface FormValues {
  text: string;
  status: 'ALL' | 'ACTIVE' | 'BANNED';
}

export default function BroadcastPage() {
  const [form] = Form.useForm<FormValues>();
  const [previewText, setPreviewText] = useState('');
  const [previewStatus, setPreviewStatus] = useState<string>('ALL');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [pendingValues, setPendingValues] = useState<BroadcastRequest | null>(null);
  const [sentResult, setSentResult] = useState<{ recipientCount: number; statusFilter: string | null } | null>(null);
  const { notification } = App.useApp();

  const sendMutation = useMutation({
    mutationFn: (data: BroadcastRequest) => broadcastApi.send(data),
    onSuccess: (result) => {
      setSentResult(result);
      setConfirmOpen(false);
      form.resetFields();
      setPreviewText('');
    },
    onError: (err: Error) => {
      notification.error({ message: 'Broadcast failed', description: err.message });
      setConfirmOpen(false);
    },
  });

  const handleConfirm = () => {
    if (pendingValues) sendMutation.mutate(pendingValues);
  };

  const handleSubmit = (values: FormValues) => {
    const payload: BroadcastRequest = {
      text: values.text,
      status: values.status === 'ALL' ? null : values.status,
    };
    setPendingValues(payload);
    setConfirmOpen(true);
  };

  const recipientLabel = previewStatus === 'ALL' ? 'все пользователи' : `пользователи со статусом ${previewStatus}`;

  if (sentResult) {
    return (
      <Result
        status="success"
        title="Рассылка отправлена!"
        subTitle={`Сообщение поставлено в очередь для ${sentResult.recipientCount} получателей${sentResult.statusFilter ? ` (статус: ${sentResult.statusFilter})` : ''}.`}
        extra={
          <Button type="primary" onClick={() => setSentResult(null)}>
            Новая рассылка
          </Button>
        }
      />
    );
  }

  return (
    <>
      <Row gutter={24}>
        <Col xs={24} lg={12}>
          <Card title="Составить рассылку" size="small">
            <Form<FormValues>
              form={form}
              layout="vertical"
              initialValues={{ status: 'ALL' }}
              onFinish={handleSubmit}
              onValuesChange={(_, values) => {
                setPreviewText(values.text ?? '');
                setPreviewStatus(values.status ?? 'ALL');
              }}
            >
              <Form.Item
                name="text"
                label="Текст сообщения"
                rules={[
                  { required: true, message: 'Введите текст' },
                  { min: 5, message: 'Минимум 5 символов' },
                  { max: 4096, message: 'Лимит Telegram: 4096 символов' },
                ]}
                extra="Поддерживается Telegram Markdown: **жирный**, *курсив*, `код`"
              >
                <Input.TextArea
                  rows={10}
                  placeholder="Введите текст рассылки..."
                  showCount
                  maxLength={4096}
                />
              </Form.Item>

              <Form.Item name="status" label="Получатели">
                <Select options={STATUS_OPTIONS} />
              </Form.Item>

              <Form.Item style={{ marginBottom: 0 }}>
                <Button
                  type="primary"
                  htmlType="submit"
                  icon={<SendOutlined />}
                  block
                  size="large"
                >
                  Отправить рассылку
                </Button>
              </Form.Item>
            </Form>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card
            title={<Space><EyeOutlined /><span>Превью Telegram</span></Space>}
            size="small"
          >
            <div style={{ background: '#1a1a2e', borderRadius: 12, padding: 16, minHeight: 200 }}>
              {previewText ? (
                <div
                  style={{
                    background: '#2a2a4e',
                    borderRadius: '4px 16px 16px 16px',
                    padding: '10px 14px',
                    maxWidth: '80%',
                    color: '#fff',
                    fontSize: 14,
                    lineHeight: 1.5,
                  }}
                  dangerouslySetInnerHTML={{ __html: renderTelegramPreview(previewText) }}
                />
              ) : (
                <Typography.Text type="secondary" style={{ color: '#666' }}>
                  Превью появится здесь...
                </Typography.Text>
              )}
            </div>

            <div style={{ marginTop: 12 }}>
              <Space>
                <Typography.Text type="secondary">Получатели:</Typography.Text>
                <Tag color={statusTagColor(previewStatus === 'ALL' ? null : previewStatus)}>
                  {previewStatus === 'ALL' ? 'Все' : previewStatus}
                </Tag>
              </Space>
            </div>
          </Card>
        </Col>
      </Row>

      <Modal
        title="Подтвердить рассылку"
        open={confirmOpen}
        onCancel={() => setConfirmOpen(false)}
        onOk={handleConfirm}
        okText="Отправить"
        okButtonProps={{ danger: true, loading: sendMutation.isPending }}
        cancelText="Отмена"
      >
        <Space direction="vertical">
          <Typography.Text>
            Вы собираетесь отправить рассылку <strong>{recipientLabel}</strong>.
          </Typography.Text>
          <Typography.Text type="secondary">
            Действие необратимо. Убедитесь в правильности текста перед отправкой.
          </Typography.Text>
          <Card size="small" style={{ marginTop: 8 }}>
            <Typography.Text style={{ whiteSpace: 'pre-wrap', fontSize: 13 }}>
              {pendingValues?.text}
            </Typography.Text>
          </Card>
        </Space>
      </Modal>
    </>
  );
}
