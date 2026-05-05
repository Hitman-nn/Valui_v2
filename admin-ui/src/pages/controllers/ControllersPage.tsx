import { useState } from 'react';
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
  TablePaginationConfig,
} from 'antd';
import {
  SearchOutlined,
  DeleteOutlined,
  SoundOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
} from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import dayjs from 'dayjs';
import { controllersApi } from '../../api/endpoints';
import type { Controller, HalPage } from '../../api/types';

const BOOKMAKERS = ['XBET', 'FONBET', 'OLIMP', 'BETCITY', 'BETBOOM'];

export default function ControllersPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage]   = useState(0);
  const [search, setSearch] = useState('');
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
      width: 100,
      render: (v: string) => <Tag>{v}</Tag>,
    },
    {
      title: 'Название / URL',
      key: 'title',
      render: (_: unknown, c: Controller) => (
        <div>
          <div style={{ fontWeight: 500 }}>{c.title ?? <Typography.Text type="secondary">—</Typography.Text>}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {c.url?.length > 60 ? c.url.substring(0, 60) + '…' : c.url}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: 'Статус',
      key: 'status',
      width: 130,
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
      width: 80,
      align: 'right' as const,
    },
    {
      title: 'Последнее событие',
      dataIndex: 'lastEventAt',
      key: 'lastEventAt',
      width: 150,
      render: (v: string | null) => v
        ? dayjs(v).format('DD.MM.YY HH:mm')
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
          placeholder="Поиск по названию / URL"
          prefix={<SearchOutlined />}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ width: 280 }}
          allowClear
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

      <Table<Controller>
        dataSource={filteredControllers}
        columns={columns}
        rowKey="id"
        loading={isLoading}
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
      />
    </>
  );
}
