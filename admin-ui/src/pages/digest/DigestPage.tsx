import { useState } from 'react';
import {
  Card,
  Table,
  Input,
  Button,
  Switch,
  Space,
  Typography,
  Modal,
  Statistic,
  Row,
  Col,
  App,
} from 'antd';
import { SearchOutlined, SendOutlined, EyeOutlined } from '@ant-design/icons';
import { useQuery, useMutation } from '@tanstack/react-query';
import { digestApi } from '../../api/endpoints';
import type { ChatDigestPreview } from '../../api/types';

/** MarkdownV2 escapes every reserved char with a leading backslash (see
 *  ChatDigestMessageFormatter.escapeMarkdown on the backend) — undo that first so a raw preview
 *  doesn't show literal backslashes, then render the same bold/italic/code markers Telegram
 *  itself would, same approach as BroadcastPage's renderTelegramPreview. */
function renderDigestPreview(text: string): string {
  const unescaped = text.replace(/\\(.)/g, '$1');
  return unescaped
    .replace(/\*(.*?)\*/g, '<strong>$1</strong>')
    .replace(/_(.*?)_/g, '<em>$1</em>')
    .replace(/`(.*?)`/g, '<code style="background:#333;padding:1px 4px;border-radius:3px">$1</code>')
    .replace(/\n/g, '<br/>');
}

export default function DigestPage() {
  const [chatIdFilter, setChatIdFilter] = useState('');
  const [dryRun, setDryRun] = useState(true);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const { notification } = App.useApp();

  const { data: previews, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['admin-digest-preview'],
    queryFn: () => digestApi.preview(),
  });

  const triggerMutation = useMutation({
    mutationFn: (dr: boolean) => digestApi.trigger(dr),
    onSuccess: (result) => {
      setConfirmOpen(false);
      notification.success({
        message: result.dryRun ? 'Dry-run завершён' : 'Дайджест отправлен',
        description: result.dryRun
          ? `${result.chatCount} чатов получили бы дайджест — ничего не отправлено.`
          : `Поставлено в очередь для ${result.chatCount} чатов.`,
      });
    },
    onError: (err: Error) => {
      setConfirmOpen(false);
      notification.error({ message: 'Не удалось запустить дайджест', description: err.message });
    },
  });

  const handleTrigger = () => {
    if (dryRun) {
      triggerMutation.mutate(true);
    } else {
      setConfirmOpen(true);
    }
  };

  const filtered: ChatDigestPreview[] = (previews ?? []).filter(
    (p) => !chatIdFilter || String(p.chatId).includes(chatIdFilter),
  );

  return (
    <>
      <Row gutter={24}>
        <Col xs={24} lg={16}>
          <Card
            title={<Space><EyeOutlined /><span>Превью по чатам</span></Space>}
            size="small"
            extra={
              <Input
                placeholder="Фильтр по chatId"
                prefix={<SearchOutlined />}
                value={chatIdFilter}
                onChange={(e) => setChatIdFilter(e.target.value)}
                style={{ width: 220 }}
                allowClear
              />
            }
          >
            <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
              Ничего не отправляется — это то же самое, что реально уйдёт каждому чату при
              следующем запуске. Проверьте цифры перед тем, как включать{' '}
              <Typography.Text code>CHAT_DIGEST_ENABLED</Typography.Text>.
            </Typography.Paragraph>
            <Table<ChatDigestPreview>
              rowKey="chatId"
              loading={isLoading || isFetching}
              dataSource={filtered}
              size="small"
              pagination={{ pageSize: 10 }}
              columns={[
                { title: 'Chat ID', dataIndex: 'chatId', width: 160 },
              ]}
              expandable={{
                expandedRowRender: (record) => (
                  <div style={{ background: '#1a1a2e', borderRadius: 12, padding: 16 }}>
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
                      dangerouslySetInnerHTML={{ __html: renderDigestPreview(record.text) }}
                    />
                  </div>
                ),
                rowExpandable: () => true,
              }}
            />
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title="Ручной запуск" size="small">
            <Space direction="vertical" style={{ width: '100%' }} size="middle">
              <Statistic title="Подходящих чатов" value={previews?.length ?? 0} />

              <Space>
                <Switch checked={dryRun} onChange={setDryRun} />
                <Typography.Text>
                  {dryRun ? 'Dry-run (только посчитать)' : 'Реальная отправка'}
                </Typography.Text>
              </Space>

              <Button
                type="primary"
                danger={!dryRun}
                icon={<SendOutlined />}
                block
                loading={triggerMutation.isPending && dryRun}
                onClick={handleTrigger}
              >
                {dryRun ? 'Посчитать (dry-run)' : 'Отправить всем чатам сейчас'}
              </Button>

              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                Реальная отправка уходит сразу во все подходящие чаты — точечного запуска на один
                чат на бэкенде нет. Расписание по умолчанию — понедельник 10:00.
              </Typography.Text>

              <Button onClick={() => refetch()} loading={isFetching}>
                Обновить превью
              </Button>
            </Space>
          </Card>
        </Col>
      </Row>

      <Modal
        title="Подтвердить отправку дайджеста"
        open={confirmOpen}
        onCancel={() => setConfirmOpen(false)}
        onOk={() => triggerMutation.mutate(false)}
        okText="Отправить всем"
        okButtonProps={{ danger: true, loading: triggerMutation.isPending }}
        cancelText="Отмена"
      >
        <Space direction="vertical">
          <Typography.Text>
            Дайджест уйдёт сразу в <strong>{previews?.length ?? 0}</strong> чатов. Действие
            необратимо — сообщения уже опубликованы в Kafka не отозвать.
          </Typography.Text>
          <Typography.Text type="secondary">
            Убедитесь, что превью слева выглядит правильно, прежде чем подтверждать.
          </Typography.Text>
        </Space>
      </Modal>
    </>
  );
}
