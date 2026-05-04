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

const PLAN_OPTIONS = [
  { label: 'All Users', value: 'ALL' },
  { label: 'FREE', value: 'FREE' },
  { label: 'PRO', value: 'PRO' },
  { label: 'PREMIUM', value: 'PREMIUM' },
];

function planTagColor(plan: string | null): string {
  switch (plan) {
    case 'PREMIUM': return 'gold';
    case 'PRO': return 'blue';
    case 'FREE': return 'default';
    default: return 'purple';
  }
}

// Very simple markdown-like preview (bold, italic, code)
function renderTelegramPreview(text: string): string {
  return text
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.*?)\*/g, '<em>$1</em>')
    .replace(/`(.*?)`/g, '<code style="background:#333;padding:1px 4px;border-radius:3px">$1</code>')
    .replace(/\n/g, '<br/>');
}

interface FormValues {
  text: string;
  planCode: 'ALL' | 'FREE' | 'PRO' | 'PREMIUM';
}

export default function BroadcastPage() {
  const [form] = Form.useForm<FormValues>();
  const [previewText, setPreviewText] = useState('');
  const [previewPlan, setPreviewPlan] = useState<string>('ALL');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [pendingValues, setPendingValues] = useState<BroadcastRequest | null>(null);
  const [sentResult, setSentResult] = useState<{ recipientCount: number; planFilter: string | null } | null>(null);
  const { notification } = App.useApp();

  const sendMutation = useMutation({
    mutationFn: broadcastApi.send,
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
    if (pendingValues) {
      sendMutation.mutate(pendingValues);
    }
  };

  const handleSubmit = (values: FormValues) => {
    const payload: BroadcastRequest = {
      text: values.text,
      planCode: values.planCode === 'ALL' ? null : values.planCode,
    };
    setPendingValues(payload);
    setConfirmOpen(true);
  };

  const recipientLabel = previewPlan === 'ALL' ? 'all users' : `${previewPlan} plan users`;

  if (sentResult) {
    return (
      <Result
        status="success"
        title="Broadcast Sent!"
        subTitle={`Message delivered to ${sentResult.recipientCount} recipient(s)${sentResult.planFilter ? ` (${sentResult.planFilter} plan)` : ''}.`}
        extra={
          <Button type="primary" onClick={() => setSentResult(null)}>
            Send Another
          </Button>
        }
      />
    );
  }

  return (
    <>
      <Row gutter={24}>
        {/* Left: form */}
        <Col xs={24} lg={12}>
          <Card title="Compose Broadcast Message" size="small">
            <Form<FormValues>
              form={form}
              layout="vertical"
              initialValues={{ planCode: 'ALL' }}
              onFinish={handleSubmit}
              onValuesChange={(_, values) => {
                setPreviewText(values.text ?? '');
                setPreviewPlan(values.planCode ?? 'ALL');
              }}
            >
              <Form.Item
                name="text"
                label="Message Text"
                rules={[
                  { required: true, message: 'Enter message text' },
                  { min: 5, message: 'Message must be at least 5 characters' },
                  { max: 4096, message: 'Telegram limit: 4096 characters' },
                ]}
                extra="Supports Telegram markdown: **bold**, *italic*, `code`"
              >
                <Input.TextArea
                  rows={10}
                  placeholder="Enter your broadcast message here..."
                  showCount
                  maxLength={4096}
                />
              </Form.Item>

              <Form.Item
                name="planCode"
                label="Target Audience"
              >
                <Select options={PLAN_OPTIONS} />
              </Form.Item>

              <Form.Item style={{ marginBottom: 0 }}>
                <Button
                  type="primary"
                  htmlType="submit"
                  icon={<SendOutlined />}
                  block
                  size="large"
                >
                  Send Broadcast
                </Button>
              </Form.Item>
            </Form>
          </Card>
        </Col>

        {/* Right: preview */}
        <Col xs={24} lg={12}>
          <Card
            title={
              <Space>
                <EyeOutlined />
                <span>Telegram Preview</span>
              </Space>
            }
            size="small"
          >
            <div
              style={{
                background: '#1a1a2e',
                borderRadius: 12,
                padding: 16,
                minHeight: 200,
              }}
            >
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
                    boxShadow: '0 1px 2px rgba(0,0,0,0.3)',
                  }}
                  dangerouslySetInnerHTML={{ __html: renderTelegramPreview(previewText) }}
                />
              ) : (
                <Typography.Text type="secondary" style={{ color: '#666' }}>
                  Message preview will appear here...
                </Typography.Text>
              )}
            </div>

            <div style={{ marginTop: 12 }}>
              <Space>
                <Typography.Text type="secondary">Target:</Typography.Text>
                <Tag color={planTagColor(previewPlan === 'ALL' ? null : previewPlan)}>
                  {previewPlan === 'ALL' ? 'All Users' : `${previewPlan} Plan`}
                </Tag>
              </Space>
            </div>
          </Card>
        </Col>
      </Row>

      <Modal
        title="Confirm Broadcast"
        open={confirmOpen}
        onCancel={() => setConfirmOpen(false)}
        onOk={handleConfirm}
        okText="Send"
        okButtonProps={{ danger: true, loading: sendMutation.isPending }}
        cancelText="Cancel"
      >
        <Space direction="vertical">
          <Typography.Text>
            You are about to send a broadcast message to{' '}
            <strong>{recipientLabel}</strong>.
          </Typography.Text>
          <Typography.Text type="secondary">
            This action cannot be undone. Make sure your message is correct before proceeding.
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
