import { useState } from 'react';
import {
  Card,
  Descriptions,
  Tabs,
  Table,
  Button,
  Modal,
  Form,
  Input,
  Space,
  Tag,
  Spin,
  App,
  Typography,
  Tooltip,
  Popconfirm,
} from 'antd';
import {
  ArrowLeftOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  SoundOutlined,
  EditOutlined,
  DeleteOutlined,
} from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useParams, useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import { controllersApi } from '../../api/endpoints';
import type { AdminEvent, Controller, HalPage, PollHistoryEntry, UpdateControllerRequest } from '../../api/types';

export default function ControllerDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { notification, modal } = App.useApp();
  const qc = useQueryClient();
  const [eventsPage, setEventsPage] = useState(0);
  const [editOpen, setEditOpen] = useState(false);
  const [editForm] = Form.useForm<UpdateControllerRequest & { title?: string }>();

  const { data: controller, isLoading } = useQuery({
    queryKey: ['controller', id],
    queryFn: () => controllersApi.get(id!),
    enabled: !!id,
  });

  const { data: eventsData, isLoading: eventsLoading } = useQuery({
    queryKey: ['controller-events', id, eventsPage],
    queryFn: () => controllersApi.events(id!, { page: eventsPage, size: 20 }),
    enabled: !!id,
  });

  const { data: pollHistory } = useQuery({
    queryKey: ['controller-poll-history', id],
    queryFn: () => controllersApi.pollHistory(id!),
    enabled: !!id,
    refetchInterval: 30_000,
  });

  const applyUpdate = (updated: Controller) => {
    qc.setQueryData(['controller', id], updated);
    qc.setQueriesData<HalPage<Controller>>(
      { queryKey: ['admin-controllers'] },
      (old) => {
        if (!old) return old;
        return {
          ...old,
          _embedded: {
            ...old._embedded,
            controllers: old._embedded.controllers.map((c) =>
              c.id === updated.id ? updated : c,
            ),
          },
        };
      },
    );
  };

  const toggleMutation = useMutation({
    mutationFn: () => controllersApi.toggle(id!),
    onSuccess: (updated) => { notification.success({ message: 'Статус обновлён' }); applyUpdate(updated); },
    onError: (e: Error) => notification.error({ message: e.message }),
  });

  const muteMutation = useMutation({
    mutationFn: () => controllersApi.setMute(id!, !controller?.isMuted),
    onSuccess: (updated) => { notification.success({ message: 'Mute обновлён' }); applyUpdate(updated); },
    onError: (e: Error) => notification.error({ message: e.message }),
  });

  const updateMutation = useMutation({
    mutationFn: (data: UpdateControllerRequest) => controllersApi.update(id!, data),
    onSuccess: (updated) => {
      notification.success({ message: 'Контроллер обновлён' });
      setEditOpen(false);
      editForm.resetFields();
      applyUpdate(updated);
    },
    onError: (e: Error) => notification.error({ message: e.message }),
  });

  const deleteMutation = useMutation({
    mutationFn: () => controllersApi.delete(id!),
    onSuccess: () => {
      notification.success({ message: 'Контроллер удалён' });
      navigate('/controllers');
    },
    onError: (e: Error) => notification.error({ message: e.message }),
  });

  const handleDelete = () => {
    modal.confirm({
      title: 'Удалить контроллер?',
      content: `${controller?.title ?? controller?.url} будет удалён безвозвратно.`,
      okButtonProps: { danger: true },
      okText: 'Удалить',
      onOk: () => deleteMutation.mutate(),
    });
  };

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 64 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!controller) {
    return <Typography.Text type="danger">Контроллер не найден</Typography.Text>;
  }

  const eventsColumns = [
    {
      title: 'Событие',
      dataIndex: 'title',
      key: 'title',
    },
    {
      title: 'External ID',
      dataIndex: 'eventExternalId',
      key: 'eventExternalId',
      width: 130,
      render: (v: string) => (
        <Typography.Text code style={{ fontSize: 11 }}>
          {v.length > 20 ? v.substring(0, 20) + '…' : v}
        </Typography.Text>
      ),
    },
    {
      title: 'Обнаружено',
      dataIndex: 'detectedAt',
      key: 'detectedAt',
      width: 150,
      render: (v: string) => dayjs(v).format('DD.MM.YY HH:mm'),
    },
    {
      title: 'Истекает',
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      width: 150,
      render: (v: string | null) => {
        if (!v) return <Typography.Text type="secondary">—</Typography.Text>;
        const expired = dayjs(v).isBefore(dayjs());
        return (
          <Tag color={expired ? 'default' : 'processing'}>
            {dayjs(v).format('DD.MM.YY HH:mm')}
          </Tag>
        );
      },
    },
  ];

  return (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/controllers')}>
          Контроллеры
        </Button>
        <Tooltip title={controller.isActive ? 'Деактивировать' : 'Активировать'}>
          <Button
            icon={controller.isActive ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
            loading={toggleMutation.isPending}
            onClick={() => toggleMutation.mutate()}
          >
            {controller.isActive ? 'Деактивировать' : 'Активировать'}
          </Button>
        </Tooltip>
        <Button
          icon={<SoundOutlined />}
          style={{ color: controller.isMuted ? '#faad14' : undefined }}
          loading={muteMutation.isPending}
          onClick={() => muteMutation.mutate()}
        >
          {controller.isMuted ? 'Unmute' : 'Mute'}
        </Button>
        <Button
          icon={<EditOutlined />}
          onClick={() => {
            editForm.setFieldsValue({
              title: controller.title ?? '',
              filterRule: controller.filterRule ?? '',
              pollIntervalSec: controller.pollIntervalSec ?? undefined,
            });
            setEditOpen(true);
          }}
        >
          Редактировать
        </Button>
        <Popconfirm
          title="Удалить контроллер?"
          description="Это действие необратимо."
          onConfirm={handleDelete}
          okButtonProps={{ danger: true }}
          okText="Удалить"
        >
          <Button danger icon={<DeleteOutlined />} loading={deleteMutation.isPending}>
            Удалить
          </Button>
        </Popconfirm>
      </Space>

      <Card style={{ marginBottom: 16 }}>
        <Descriptions
          title={
            <Space>
              <Tag>{controller.bookmaker}</Tag>
              <span>{controller.title ?? <Typography.Text type="secondary">Без названия</Typography.Text>}</span>
              <Tag color={controller.isActive ? 'success' : 'default'}>
                {controller.isActive ? 'Active' : 'Inactive'}
              </Tag>
              {controller.isMuted && <Tag color="warning">Muted</Tag>}
            </Space>
          }
          column={{ xs: 1, sm: 2, lg: 3 }}
          bordered
          size="small"
        >
          <Descriptions.Item label="ID">
            <Typography.Text code style={{ fontSize: 11 }}>{controller.id}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="Букмекер">
            <Tag>{controller.bookmaker}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="Тип">{controller.type}</Descriptions.Item>
          <Descriptions.Item label="URL" span={2}>
            <Typography.Text
              copyable
              ellipsis={{ tooltip: controller.url }}
              style={{ maxWidth: 400 }}
            >
              {controller.url}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="Фильтр">
            {controller.filterRule
              ? <Typography.Text code>{controller.filterRule}</Typography.Text>
              : <Typography.Text type="secondary">—</Typography.Text>}
          </Descriptions.Item>
          <Descriptions.Item label="Интервал опроса">
            {controller.pollIntervalSec != null
              ? <Tag color="blue">каждые {controller.pollIntervalSec} сек</Tag>
              : <Typography.Text type="secondary">—</Typography.Text>}
          </Descriptions.Item>
          <Descriptions.Item label="Владелец (Telegram ID)">
            {controller.ownerTelegramId ?? <Typography.Text type="secondary">—</Typography.Text>}
          </Descriptions.Item>
          <Descriptions.Item label="Чат для уведомлений">
            {controller.notificationChatId ?? <Typography.Text type="secondary">—</Typography.Text>}
          </Descriptions.Item>
          <Descriptions.Item label="Событий обнаружено">{controller.detectedEventsCount}</Descriptions.Item>
          <Descriptions.Item label="Последнее событие">
            {controller.lastEventAt
              ? dayjs(controller.lastEventAt).format('DD.MM.YYYY HH:mm')
              : <Typography.Text type="secondary">—</Typography.Text>}
          </Descriptions.Item>
          <Descriptions.Item label="Последний опрос">
            {controller.lastCheckedAt
              ? <Tooltip title={dayjs(controller.lastCheckedAt).format('DD.MM.YYYY HH:mm:ss')}>
                  <span>{dayjs(controller.lastCheckedAt).format('DD.MM.YYYY HH:mm:ss')}</span>
                </Tooltip>
              : <Typography.Text type="secondary">ещё не запускался</Typography.Text>}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="История опросов" size="small" style={{ marginBottom: 16 }}>
        {pollHistory && pollHistory.length > 0 ? (
          <Table<PollHistoryEntry>
            dataSource={pollHistory}
            rowKey="startedAt"
            size="small"
            pagination={false}
            columns={[
              {
                title: 'Время',
                dataIndex: 'startedAt',
                key: 'startedAt',
                width: 170,
                render: (v: string) => dayjs(v).format('DD.MM.YY HH:mm:ss'),
              },
              {
                title: 'Длительность',
                dataIndex: 'durationMs',
                key: 'durationMs',
                width: 130,
                render: (v: number) => (
                  <Tag color={v > 3000 ? 'warning' : v > 1000 ? 'default' : 'success'}>
                    {v < 1000 ? `${v} мс` : `${(v / 1000).toFixed(1)} с`}
                  </Tag>
                ),
              },
              {
                title: 'Событий',
                dataIndex: 'eventsFound',
                key: 'eventsFound',
                width: 110,
                render: (v: number, row: PollHistoryEntry) =>
                  row.status === 'error' ? (
                    <Tag color="error">Ошибка</Tag>
                  ) : v > 0 ? (
                    <Tag color="success">+{v} новых</Tag>
                  ) : (
                    <Typography.Text type="secondary">нет новых</Typography.Text>
                  ),
              },
            ]}
          />
        ) : (
          <Typography.Text type="secondary">
            История появится после первого опроса
          </Typography.Text>
        )}
      </Card>

      <Tabs
        defaultActiveKey="events"
        items={[
          {
            key: 'events',
            label: `События (${eventsData?.totalElements ?? 0})`,
            children: (
              <Table<AdminEvent>
                dataSource={eventsData?.content ?? []}
                columns={eventsColumns}
                rowKey="id"
                loading={eventsLoading}
                size="middle"
                pagination={{
                  current: eventsPage + 1,
                  pageSize: 20,
                  total: eventsData?.totalElements ?? 0,
                  onChange: (p) => setEventsPage(p - 1),
                  showSizeChanger: false,
                  showTotal: (t) => `Всего ${t}`,
                }}
              />
            ),
          },
        ]}
      />

      <Modal
        title="Редактировать контроллер"
        open={editOpen}
        onCancel={() => { setEditOpen(false); editForm.resetFields(); }}
        onOk={() => editForm.submit()}
        okText="Сохранить"
        confirmLoading={updateMutation.isPending}
      >
        <Form
          form={editForm}
          layout="vertical"
          onFinish={(values) => updateMutation.mutate(values)}
        >
          <Form.Item name="title" label="Название">
            <Input />
          </Form.Item>
          <Form.Item name="filterRule" label="Фильтр (regex)">
            <Input placeholder="Реал|Барселона" />
          </Form.Item>
          <Form.Item name="pollIntervalSec" label="Интервал опроса (сек)">
            <Input type="number" min={10} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
