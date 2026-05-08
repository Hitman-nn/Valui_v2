import { useRef, useState } from 'react';
import {
  Table,
  Input,
  Select,
  Space,
  Button,
  Tag,
  Tooltip,
  App,
  Typography,
  Modal,
  Form,
  InputNumber,
  type TablePaginationConfig,
} from 'antd';
import { ClockCircleOutlined } from '@ant-design/icons';
import {
  SearchOutlined,
  DeleteOutlined,
  SoundOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  FilterOutlined,
} from '@ant-design/icons';
import type { FilterDropdownProps } from 'antd/es/table/interface';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import dayjs from 'dayjs';
import { controllersApi } from '../../api/endpoints';
import type { Controller, HalPage } from '../../api/types';

const BOOKMAKERS = ['XBET', 'FONBET', 'OLIMP', 'BETCITY', 'BETBOOM'];

// ── text-search dropdown (reusable для любого поля) ───────────────────────────
function textFilterDropdown(
  placeholder: string,
  onFilter: (value: string, record: Controller) => boolean,
) {
  return {
    filterDropdown: ({ setSelectedKeys, selectedKeys, confirm, clearFilters }: FilterDropdownProps) => (
      <div style={{ padding: 8 }} onKeyDown={(e) => e.stopPropagation()}>
        <Input
          autoFocus
          placeholder={placeholder}
          value={selectedKeys[0] as string}
          onChange={(e) => setSelectedKeys(e.target.value ? [e.target.value] : [])}
          onPressEnter={() => confirm()}
          style={{ marginBottom: 8, display: 'block' }}
        />
        <Space>
          <Button type="primary" size="small" icon={<SearchOutlined />} onClick={() => confirm()}>
            Найти
          </Button>
          <Button size="small" onClick={() => { clearFilters?.(); confirm(); }}>
            Сброс
          </Button>
        </Space>
      </div>
    ),
    filterIcon: (filtered: boolean) => (
      <SearchOutlined style={{ color: filtered ? '#1677ff' : undefined }} />
    ),
    onFilter: (value: boolean | React.Key, record: Controller) =>
      onFilter(String(value).toLowerCase(), record),
  };
}

export default function ControllersPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [intervalModalOpen, setIntervalModalOpen] = useState(false);
  const [intervalForm] = Form.useForm<{ pollIntervalSec: number }>();
  const searchRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const bookmakerFilter = searchParams.get('bookmaker') ?? 'ALL';
  const isActiveFilter  = searchParams.get('active');
  const isMutedFilter   = searchParams.get('muted');
  const navigate        = useNavigate();
  const { notification, modal } = App.useApp();
  const qc = useQueryClient();

  const setBookmaker = (value: string) => {
    setPage(0);
    const next = new URLSearchParams(searchParams);
    if (value === 'ALL') next.delete('bookmaker'); else next.set('bookmaker', value);
    setSearchParams(next);
  };

  const { data, isLoading } = useQuery({
    queryKey: ['admin-controllers', page, bookmakerFilter, isActiveFilter, isMutedFilter],
    queryFn: () => controllersApi.list({
      page,
      size: 25,
      ...(bookmakerFilter !== 'ALL' ? { bookmaker: bookmakerFilter } : {}),
      ...(isActiveFilter !== null ? { isActive: isActiveFilter === 'true' } : {}),
      ...(isMutedFilter  !== null ? { isMuted:  isMutedFilter  === 'true' } : {}),
    }),
  });

  const controllers: Controller[] = data?._embedded?.controllers ?? [];
  const total = data?.page?.totalElements ?? 0;

  const filteredControllers = controllers.filter((c) =>
    !search ||
    c.title?.toLowerCase().includes(search.toLowerCase()) ||
    c.url?.toLowerCase().includes(search.toLowerCase()) ||
    c.bookmaker?.toLowerCase().includes(search.toLowerCase()),
  );

  const patchCache = (updated: Controller) => {
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

  const refetchList = () => qc.invalidateQueries({ queryKey: ['admin-controllers'] });

  const toggleMutation = useMutation({
    mutationFn: (c: Controller) => controllersApi.toggle(c.id),
    onSuccess: (updated) => { notification.success({ message: 'Статус обновлён' }); patchCache(updated); },
    onError: (e: Error) => notification.error({ message: e.message }),
  });

  const muteMutation = useMutation({
    mutationFn: (c: Controller) => controllersApi.setMute(c.id, !c.isMuted),
    onSuccess: (updated) => { notification.success({ message: 'Mute обновлён' }); patchCache(updated); },
    onError: (e: Error) => notification.error({ message: e.message }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => controllersApi.delete(id),
    onSuccess: () => { notification.success({ message: 'Контроллер удалён' }); refetchList(); },
    onError: (e: Error) => notification.error({ message: e.message }),
  });

  const bulkIntervalMutation = useMutation({
    mutationFn: ({ ids, pollIntervalSec }: { ids: string[]; pollIntervalSec: number }) =>
      controllersApi.bulkSetInterval(ids, pollIntervalSec),
    onSuccess: () => {
      notification.success({ message: `Интервал обновлён для ${selectedIds.length} контроллеров` });
      setSelectedIds([]);
      setIntervalModalOpen(false);
      intervalForm.resetFields();
      refetchList();
    },
    onError: (e: Error) => notification.error({ message: e.message }),
  });

  const handleDelete = (c: Controller) => {
    modal.confirm({
      title: 'Удалить контроллер?',
      content: `${c.title ?? c.url} будет удалён безвозвратно.`,
      okButtonProps: { danger: true },
      okText: 'Удалить',
      onOk: () => deleteMutation.mutate(c.id),
    });
  };

  const columns = [
    {
      title: 'Букмекер',
      dataIndex: 'bookmaker',
      key: 'bookmaker',
      width: 110,
      sorter: (a: Controller, b: Controller) => a.bookmaker.localeCompare(b.bookmaker),
      filters: BOOKMAKERS.map((b) => ({ text: b, value: b })),
      onFilter: (value: boolean | React.Key, record: Controller) => record.bookmaker === String(value),
      filterIcon: (filtered: boolean) => (
        <FilterOutlined style={{ color: filtered ? '#1677ff' : undefined }} />
      ),
      render: (v: string) => <Tag>{v}</Tag>,
    },
    {
      title: 'Название / URL',
      key: 'title',
      sorter: (a: Controller, b: Controller) =>
        (a.title ?? a.url ?? '').localeCompare(b.title ?? b.url ?? ''),
      ...textFilterDropdown(
        'Поиск по названию / URL',
        (q, c) =>
          Boolean(c.title?.toLowerCase().includes(q) || c.url?.toLowerCase().includes(q)),
      ),
      render: (_: unknown, c: Controller) => (
        <div>
          <div style={{ fontWeight: 500 }}>
            {c.title ?? <Typography.Text type="secondary">—</Typography.Text>}
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {c.url?.length > 60 ? c.url.substring(0, 60) + '…' : c.url}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: 'Статус',
      key: 'status',
      width: 140,
      sorter: (a: Controller, b: Controller) => {
        // Active > Muted > Inactive
        const score = (c: Controller) => (c.isActive ? 2 : 0) + (c.isMuted ? -1 : 0);
        return score(b) - score(a);
      },
      filters: [
        { text: '🟢 Активен',    value: 'active'   },
        { text: '🔴 Остановлен', value: 'inactive' },
        { text: '🔕 Muted',      value: 'muted'    },
      ],
      onFilter: (value: boolean | React.Key, record: Controller) => {
        if (value === 'active')   return record.isActive && !record.isMuted;
        if (value === 'inactive') return !record.isActive;
        if (value === 'muted')    return record.isMuted;
        return true;
      },
      filterIcon: (filtered: boolean) => (
        <FilterOutlined style={{ color: filtered ? '#1677ff' : undefined }} />
      ),
      render: (_: unknown, c: Controller) => (
        <Space size={4}>
          <Tag color={c.isActive ? 'success' : 'default'}>{c.isActive ? 'Active' : 'Inactive'}</Tag>
          {c.isMuted && <Tag color="warning">Muted</Tag>}
        </Space>
      ),
    },
    {
      title: 'События',
      dataIndex: 'detectedEventsCount',
      key: 'events',
      width: 90,
      align: 'right' as const,
      sorter: (a: Controller, b: Controller) => a.detectedEventsCount - b.detectedEventsCount,
    },
    {
      title: 'Последнее событие',
      dataIndex: 'lastEventAt',
      key: 'lastEventAt',
      width: 145,
      sorter: (a: Controller, b: Controller) =>
        (a.lastEventAt ? dayjs(a.lastEventAt).unix() : 0) -
        (b.lastEventAt ? dayjs(b.lastEventAt).unix() : 0),
      render: (v: string | null) => v
        ? dayjs(v).format('DD.MM.YY HH:mm')
        : <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: 'Последний опрос',
      dataIndex: 'lastCheckedAt',
      key: 'lastCheckedAt',
      width: 150,
      sorter: (a: Controller, b: Controller) =>
        (a.lastCheckedAt ? dayjs(a.lastCheckedAt).unix() : 0) -
        (b.lastCheckedAt ? dayjs(b.lastCheckedAt).unix() : 0),
      render: (v: string | null) => v
        ? (
          <Tooltip title={dayjs(v).format('DD.MM.YYYY HH:mm:ss')}>
            <span>{dayjs(v).format('DD.MM.YY HH:mm:ss')}</span>
          </Tooltip>
        )
        : <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: 'Интервал',
      dataIndex: 'pollIntervalSec',
      key: 'pollIntervalSec',
      width: 90,
      align: 'right' as const,
      sorter: (a: Controller, b: Controller) =>
        (a.pollIntervalSec ?? 0) - (b.pollIntervalSec ?? 0),
      filters: [10, 15, 20, 30, 60].map((s) => ({ text: `${s}с`, value: s })),
      onFilter: (value: boolean | React.Key, record: Controller) =>
        record.pollIntervalSec === Number(value),
      filterIcon: (filtered: boolean) => (
        <FilterOutlined style={{ color: filtered ? '#1677ff' : undefined }} />
      ),
      render: (v: number | null) => v != null
        ? <Tag>{v}с</Tag>
        : <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: '',
      key: 'actions',
      width: 90,
      render: (_: unknown, c: Controller) => (
        <Space size={4}>
          <Tooltip title={c.isActive ? 'Деактивировать' : 'Активировать'}>
            <Button
              size="small"
              type="text"
              icon={c.isActive ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
              onClick={(e) => { e.stopPropagation(); toggleMutation.mutate(c); }}
            />
          </Tooltip>
          <Tooltip title={c.isMuted ? 'Unmute' : 'Mute'}>
            <Button
              size="small"
              type="text"
              icon={<SoundOutlined />}
              style={{ color: c.isMuted ? '#faad14' : undefined }}
              onClick={(e) => { e.stopPropagation(); muteMutation.mutate(c); }}
            />
          </Tooltip>
          <Tooltip title="Удалить">
            <Button
              size="small"
              type="text"
              danger
              icon={<DeleteOutlined />}
              onClick={(e) => { e.stopPropagation(); handleDelete(c); }}
            />
          </Tooltip>
        </Space>
      ),
    },
  ];

  const handleTableChange = (p: TablePaginationConfig) => setPage((p.current ?? 1) - 1);

  return (
    <>
      <Space style={{ marginBottom: 16, flexWrap: 'wrap' }} size="middle">
        <Input
          placeholder="Быстрый поиск по названию / URL"
          prefix={<SearchOutlined />}
          value={search}
          onChange={(e) => {
            const v = e.target.value;
            if (searchRef.current) clearTimeout(searchRef.current);
            searchRef.current = setTimeout(() => setSearch(v), 200);
            if (!v) setSearch('');
          }}
          style={{ width: 280 }}
          allowClear
          onClear={() => setSearch('')}
        />
        <Select
          value={bookmakerFilter}
          onChange={setBookmaker}
          style={{ width: 140 }}
          options={[
            { label: 'Все букмекеры', value: 'ALL' },
            ...BOOKMAKERS.map((b) => ({ label: b, value: b })),
          ]}
        />
      </Space>

      {selectedIds.length > 0 && (
        <Space
          style={{
            marginBottom: 12,
            padding: '8px 16px',
            background: '#e6f4ff',
            borderRadius: 6,
            display: 'flex',
            alignItems: 'center',
          }}
        >
          <Typography.Text strong>Выбрано: {selectedIds.length}</Typography.Text>
          <Button
            icon={<ClockCircleOutlined />}
            onClick={() => setIntervalModalOpen(true)}
          >
            Изменить интервал
          </Button>
          <Button type="text" onClick={() => setSelectedIds([])}>
            Снять выделение
          </Button>
        </Space>
      )}

      <Table<Controller>
        dataSource={filteredControllers}
        columns={columns}
        rowKey="id"
        loading={isLoading}
        rowSelection={{
          selectedRowKeys: selectedIds,
          onChange: (keys) => setSelectedIds(keys as string[]),
        }}
        pagination={{
          current: page + 1,
          pageSize: 25,
          total,
          showSizeChanger: false,
          showTotal: (t) => `Всего ${t}`,
        }}
        onChange={handleTableChange}
        onRow={(c) => ({
          onClick: () => navigate(`/controllers/${c.id}`),
          style: { cursor: 'pointer' },
        })}
        size="middle"
        scroll={{ x: 'max-content' }}
      />

      <Modal
        title={`Изменить интервал (${selectedIds.length} контроллеров)`}
        open={intervalModalOpen}
        onCancel={() => { setIntervalModalOpen(false); intervalForm.resetFields(); }}
        onOk={() => intervalForm.submit()}
        okText="Применить"
        confirmLoading={bulkIntervalMutation.isPending}
      >
        <Form
          form={intervalForm}
          layout="vertical"
          onFinish={(v) => bulkIntervalMutation.mutate({ ids: selectedIds, pollIntervalSec: v.pollIntervalSec })}
        >
          <Form.Item
            name="pollIntervalSec"
            label="Интервал опроса (сек)"
            rules={[
              { required: true, message: 'Введите значение' },
              { type: 'number', min: 5, message: 'Минимум 5 секунд' },
            ]}
          >
            <InputNumber min={5} style={{ width: '100%' }} addonAfter="сек" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
