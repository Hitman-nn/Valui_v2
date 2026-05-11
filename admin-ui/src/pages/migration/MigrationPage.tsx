import { useState, useCallback } from 'react';
import {
  Upload,
  Button,
  Card,
  Collapse,
  Table,
  Select,
  InputNumber,
  Input,
  Space,
  Typography,
  Tag,
  Modal,
  Alert,
  Checkbox,
  Statistic,
  Row,
  Col,
  Divider,
  message,
  Spin,
} from 'antd';
import type { UploadFile } from 'antd';
import {
  InboxOutlined,
  SearchOutlined,
  ThunderboltOutlined,
  CheckCircleOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { migrationApi, usersApi } from '../../api/endpoints';
import type {
  ParsedMigration,
  ChatGroup,
  LegacyControllerPreview,
  ChatMapping,
} from '../../api/types';
import type { UserSummary } from '../../api/types';

const { Title, Text } = Typography;
const { Dragger } = Upload;
const { Panel } = Collapse;

// ── client-side helpers ───────────────────────────────────────────────────────

function detectBookmaker(url: string): string | null {
  if (!url) return null;
  if (url.includes('fon.bet'))                                      return 'FONBET';
  if (url.includes('1xbet') || url.includes('1xstavka'))            return 'XBET';
  if (url.includes('olimp.bet'))                                    return 'OLIMP';
  if (url.includes('betcity'))                                      return 'BETCITY';
  if (url.includes('betboom'))                                      return 'BETBOOM';
  return null;
}

function cleanTitle(title: string | null | undefined): string | null {
  if (!title) return null;
  const s = title.replace(/^⚠️\s*/, '').trim();
  const idx = s.indexOf(':');
  if (idx >= 0 && idx < s.length - 1) {
    const after = s.substring(idx + 1).trim();
    if (after) return after;
  }
  return s;
}

interface RawEvent  { eventId?: string }
interface RawCtrl   { chatid?: string; link?: string; title?: string; ruleFilter?: string; events?: RawEvent[] }
interface RawRoot   { controller?: RawCtrl[] }

function parseFile(file: File): Promise<{ parsed: ParsedMigration; raw: RawCtrl[] }> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const root: RawRoot = JSON.parse(e.target?.result as string);
        const all: RawCtrl[] = (root.controller ?? []).filter(c => c.chatid && c.link);

        const grouped = new Map<string, RawCtrl[]>();
        for (const c of all) {
          const key = c.chatid!;
          if (!grouped.has(key)) grouped.set(key, []);
          grouped.get(key)!.push(c);
        }

        let unknownBookmakerCount = 0;
        const groups: ChatGroup[] = [];
        for (const [chatId, ctrls] of grouped) {
          const controllers: LegacyControllerPreview[] = ctrls.map(c => {
            const bk = detectBookmaker(c.link!);
            if (!bk) unknownBookmakerCount++;
            return {
              link: c.link!,
              originalTitle: c.title ?? null,
              cleanTitle: cleanTitle(c.title),
              bookmaker: bk,
              controllerType: 'TOURNAMENT',
              eventCount: c.events?.length ?? 0,
              ruleFilter: c.ruleFilter ?? null,
            };
          });
          groups.push({ chatId, controllerCount: ctrls.length, controllers });
        }

        resolve({ parsed: { groups, totalControllers: all.length, unknownBookmakerCount }, raw: all });
      } catch (err) {
        reject(err);
      }
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsText(file);
  });
}

// ── labels ────────────────────────────────────────────────────────────────────

const BK_LABELS: Record<string, string> = {
  FONBET: 'Fonbet', XBET: '1xBet', OLIMP: 'Olimp', BETCITY: 'BetCity', BETBOOM: 'BetBoom',
};

function bkTag(bk: string | null) {
  if (!bk) return <Tag color="red">Неизвестный</Tag>;
  return <Tag color="blue">{BK_LABELS[bk] ?? bk}</Tag>;
}

// ── per-group state ───────────────────────────────────────────────────────────

interface GroupMapping {
  userId: string | null;
  notificationChatId: string;
  selectedLinks: Set<string>;
  allSelected: boolean;
}

function initGroupMapping(group: ChatGroup): GroupMapping {
  return {
    userId: null,
    notificationChatId: group.chatId,
    selectedLinks: new Set(group.controllers.map(c => c.link)),
    allSelected: true,
  };
}

// ── component ─────────────────────────────────────────────────────────────────

export default function MigrationPage() {
  const [parsing,  setParsing]  = useState(false);
  const [parsed,   setParsed]   = useState<ParsedMigration | null>(null);
  const [rawCtrls, setRawCtrls] = useState<RawCtrl[]>([]);

  const [groupMappings,   setGroupMappings]   = useState<Record<string, GroupMapping>>({});
  const [pollIntervalSec, setPollIntervalSec] = useState(60);

  const [dryRunResult,    setDryRunResult]    = useState<import('../../api/types').DryRunResult | null>(null);
  const [dryRunLoading,   setDryRunLoading]   = useState(false);
  const [showModal,       setShowModal]       = useState(false);

  const [executeLoading,  setExecuteLoading]  = useState(false);
  const [migrationResult, setMigrationResult] = useState<import('../../api/types').MigrationResult | null>(null);

  const { data: usersPage } = useQuery({
    queryKey: ['migration-users'],
    queryFn: () => usersApi.list({ size: 200 }),
  });
  const users: UserSummary[] = usersPage?._embedded?.users ?? [];
  const userOptions = users.map(u => ({
    value: u.id,
    label: u.username ? `@${u.username} (${u.telegramId})` : `${u.firstName ?? '—'} (${u.telegramId})`,
  }));

  // ── file drop ──────────────────────────────────────────────────────────────

  const handleDrop = useCallback(async (file: File) => {
    setParsing(true);
    setParsed(null);
    setRawCtrls([]);
    setGroupMappings({});
    setDryRunResult(null);
    setMigrationResult(null);
    try {
      const { parsed: p, raw } = await parseFile(file);
      setParsed(p);
      setRawCtrls(raw);
      const init: Record<string, GroupMapping> = {};
      for (const g of p.groups) init[g.chatId] = initGroupMapping(g);
      setGroupMappings(init);
      message.success(`Разобрано ${p.totalControllers} контроллеров из ${p.groups.length} чатов`);
    } catch (err) {
      message.error(`Ошибка чтения файла: ${(err as Error).message}`);
    } finally {
      setParsing(false);
    }
  }, []);

  // ── group handlers ─────────────────────────────────────────────────────────

  const setUserId = (chatId: string, v: string) =>
    setGroupMappings(p => ({ ...p, [chatId]: { ...p[chatId], userId: v } }));

  const setNotifChat = (chatId: string, v: string) =>
    setGroupMappings(p => ({ ...p, [chatId]: { ...p[chatId], notificationChatId: v } }));

  const toggleAll = (chatId: string, group: ChatGroup, checked: boolean) =>
    setGroupMappings(p => ({
      ...p,
      [chatId]: {
        ...p[chatId],
        allSelected: checked,
        selectedLinks: checked ? new Set(group.controllers.map(c => c.link)) : new Set(),
      },
    }));

  const toggleLink = (chatId: string, link: string, checked: boolean) =>
    setGroupMappings(p => {
      const gm = { ...p[chatId] };
      const sel = new Set(gm.selectedLinks);
      checked ? sel.add(link) : sel.delete(link);
      return { ...p, [chatId]: { ...gm, selectedLinks: sel, allSelected: false } };
    });

  // ── build request ──────────────────────────────────────────────────────────

  function buildRequest(withEvents: boolean) {
    const selectedPairs = new Set(
      Object.entries(groupMappings).flatMap(([chatId, gm]) =>
        [...gm.selectedLinks].map(link => `${chatId}::${link}`)
      )
    );

    const chatMappings: ChatMapping[] = Object.entries(groupMappings)
      .filter(([, gm]) => gm.userId && gm.selectedLinks.size > 0)
      .map(([chatId, gm]) => ({
        chatId,
        userId: gm.userId!,
        notificationChatId: Number(gm.notificationChatId) || Number(chatId),
      }));

    const controllers = rawCtrls
      .filter(c => c.chatid && c.link && selectedPairs.has(`${c.chatid!}::${c.link!}`))
      .map(c => ({
        chatId:    c.chatid!,
        link:      c.link!,
        title:     cleanTitle(c.title) ?? '',
        ruleFilter: c.ruleFilter ?? null,
        eventIds:  withEvents
          ? (c.events ?? []).map(e => e.eventId).filter(Boolean) as string[]
          : [],
      }));

    return { controllers, chatMappings, pollIntervalSec };
  }

  function validate() {
    const unmapped = parsed?.groups.filter(g => {
      const gm = groupMappings[g.chatId];
      return gm && gm.selectedLinks.size > 0 && !gm.userId;
    }) ?? [];
    if (unmapped.length > 0) {
      message.warning(`Назначьте владельца для: ${unmapped.map(g => g.chatId).join(', ')}`);
      return false;
    }
    return true;
  }

  // ── dry-run ────────────────────────────────────────────────────────────────

  const handleDryRun = async () => {
    if (!validate()) return;
    setDryRunLoading(true);
    try {
      const result = await migrationApi.dryRun(buildRequest(false));
      setDryRunResult(result);
      setShowModal(true);
    } catch (err: unknown) {
      const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        ?? (err as Error)?.message ?? '';
      message.error(`Ошибка dry-run: ${detail}`, 8);
    } finally {
      setDryRunLoading(false);
    }
  };

  // ── execute ────────────────────────────────────────────────────────────────

  const handleExecute = async () => {
    setExecuteLoading(true);
    setShowModal(false);
    try {
      const result = await migrationApi.execute(buildRequest(true));
      setMigrationResult(result);
      message.success(`Импортировано ${result.imported} контроллеров`);
    } catch (err: unknown) {
      const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        ?? (err as Error)?.message ?? '';
      message.error(`Ошибка выполнения: ${detail}`, 8);
    } finally {
      setExecuteLoading(false);
    }
  };

  // ── table ──────────────────────────────────────────────────────────────────

  function renderTable(group: ChatGroup, gm: GroupMapping) {
    const cols = [
      {
        title: '', width: 40,
        render: (_: unknown, row: LegacyControllerPreview) => (
          <Checkbox checked={gm.selectedLinks.has(row.link)}
            onChange={e => toggleLink(group.chatId, row.link, e.target.checked)} />
        ),
      },
      {
        title: 'Название', dataIndex: 'cleanTitle', ellipsis: true,
        sorter: (a: LegacyControllerPreview, b: LegacyControllerPreview) =>
          (a.cleanTitle ?? '').localeCompare(b.cleanTitle ?? ''),
        render: (v: string | null, row: LegacyControllerPreview) => (
          <Space direction="vertical" size={0}>
            <Text>{v || '—'}</Text>
            {row.originalTitle !== v && (
              <Text type="secondary" style={{ fontSize: 11 }}>было: {row.originalTitle}</Text>
            )}
          </Space>
        ),
      },
      {
        title: 'Букмекер', dataIndex: 'bookmaker', width: 110,
        sorter: (a: LegacyControllerPreview, b: LegacyControllerPreview) =>
          (a.bookmaker ?? '').localeCompare(b.bookmaker ?? ''),
        render: bkTag,
      },
      {
        title: 'Тип', dataIndex: 'controllerType', width: 110,
        sorter: (a: LegacyControllerPreview, b: LegacyControllerPreview) =>
          a.controllerType.localeCompare(b.controllerType),
        render: (v: string) => <Tag>{v}</Tag>,
      },
      {
        title: 'Событий', dataIndex: 'eventCount', width: 90,
        sorter: (a: LegacyControllerPreview, b: LegacyControllerPreview) => a.eventCount - b.eventCount,
        render: (n: number) => <Text type="secondary">{n}</Text>,
      },
      {
        title: 'Фильтр', dataIndex: 'ruleFilter', width: 80,
        sorter: (a: LegacyControllerPreview, b: LegacyControllerPreview) =>
          (a.ruleFilter ? 1 : 0) - (b.ruleFilter ? 1 : 0),
        render: (v: string | null) => v ? <Tag color="orange">есть</Tag> : <Tag>нет</Tag>,
      },
    ];
    return (
      <Table rowKey="link" size="small" dataSource={group.controllers}
        columns={cols} pagination={{ pageSize: 20, hideOnSinglePage: true }} scroll={{ x: 700 }} />
    );
  }

  // ── render ─────────────────────────────────────────────────────────────────

  const totalSelected = Object.values(groupMappings).reduce((a, gm) => a + gm.selectedLinks.size, 0);

  return (
    <div style={{ maxWidth: 1100, margin: '0 auto' }}>
      <Title level={4} style={{ marginBottom: 24 }}>Миграция из chatConfig.json</Title>

      <Card style={{ marginBottom: 24 }}>
        <Spin spinning={parsing}>
          <Dragger accept=".json" showUploadList={false}
            beforeUpload={(f: UploadFile & File) => { handleDrop(f as File); return false; }}
            style={{ padding: '24px 0' }}>
            <p className="ant-upload-drag-icon"><InboxOutlined /></p>
            <p className="ant-upload-text">Перетащите chatConfig.json или нажмите для выбора</p>
            <p className="ant-upload-hint">Файл читается в браузере — ничего не загружается на сервер</p>
          </Dragger>
        </Spin>

        {parsed && (
          <Alert type="info" style={{ marginTop: 16 }} message={
            <Space>
              <Text>Найдено <b>{parsed.totalControllers}</b> контроллеров в <b>{parsed.groups.length}</b> чатах</Text>
              {parsed.unknownBookmakerCount > 0 && (
                <Tag icon={<WarningOutlined />} color="warning">{parsed.unknownBookmakerCount} неизвестных букмекеров</Tag>
              )}
            </Space>
          } />
        )}
      </Card>

      {parsed && (
        <>
          <Collapse defaultActiveKey={parsed.groups.map(g => g.chatId)} style={{ marginBottom: 24 }}>
            {parsed.groups.map(group => {
              const gm = groupMappings[group.chatId];
              if (!gm) return null;
              return (
                <Panel key={group.chatId} header={
                  <Space>
                    <Text strong>chatid: {group.chatId}</Text>
                    <Tag>{group.controllerCount} контроллеров</Tag>
                    {gm.selectedLinks.size < group.controllerCount && (
                      <Tag color="blue">выбрано {gm.selectedLinks.size}</Tag>
                    )}
                  </Space>
                }>
                  <Row gutter={16} style={{ marginBottom: 16 }}>
                    <Col span={10}>
                      <Space direction="vertical" size={4} style={{ width: '100%' }}>
                        <Text type="secondary">Владелец контроллеров</Text>
                        <Select showSearch style={{ width: '100%' }} placeholder="Выберите пользователя"
                          options={userOptions}
                          filterOption={(input, opt) =>
                            (opt?.label ?? '').toLowerCase().includes(input.toLowerCase())}
                          value={gm.userId ?? undefined}
                          onChange={(v: string) => setUserId(group.chatId, v)}
                          suffixIcon={<SearchOutlined />} />
                      </Space>
                    </Col>
                    <Col span={10}>
                      <Space direction="vertical" size={4} style={{ width: '100%' }}>
                        <Text type="secondary">Chat для уведомлений (notification_chat_id)</Text>
                        <Input value={gm.notificationChatId}
                          onChange={e => setNotifChat(group.chatId, e.target.value)}
                          placeholder="Telegram chat ID" />
                      </Space>
                    </Col>
                    <Col span={4}>
                      <Space direction="vertical" size={4}>
                        <Text type="secondary">Выборка</Text>
                        <Checkbox checked={gm.allSelected}
                          onChange={e => toggleAll(group.chatId, group, e.target.checked)}>
                          Все {group.controllerCount}
                        </Checkbox>
                      </Space>
                    </Col>
                  </Row>
                  {renderTable(group, gm)}
                </Panel>
              );
            })}
          </Collapse>

          <Card>
            <Row gutter={24} align="middle">
              <Col>
                <Space direction="vertical" size={4}>
                  <Text type="secondary">Интервал опроса (сек)</Text>
                  <InputNumber min={10} max={3600} value={pollIntervalSec}
                    onChange={v => setPollIntervalSec(v ?? 60)} style={{ width: 120 }} />
                </Space>
              </Col>
              <Col flex="auto" />
              <Col>
                <Text type="secondary" style={{ marginRight: 16 }}>
                  Выбрано: <b>{totalSelected}</b>
                </Text>
                <Button type="primary" icon={<SearchOutlined />}
                  loading={dryRunLoading} onClick={handleDryRun} size="large">
                  Предварительный просмотр
                </Button>
              </Col>
            </Row>
          </Card>
        </>
      )}

      {migrationResult && (
        <Card style={{ marginTop: 24 }}>
          <Alert type={migrationResult.failed > 0 ? 'warning' : 'success'}
            icon={<CheckCircleOutlined />} message="Миграция завершена" style={{ marginBottom: 16 }} />
          <Row gutter={16}>
            <Col span={6}><Statistic title="Импортировано" value={migrationResult.imported} valueStyle={{ color: '#52c41a' }} /></Col>
            <Col span={6}><Statistic title="Дублей (пропущено)" value={migrationResult.skipped} /></Col>
            <Col span={6}><Statistic title="Ошибок" value={migrationResult.failed}
              valueStyle={migrationResult.failed > 0 ? { color: '#f5222d' } : undefined} /></Col>
            <Col span={6}><Statistic title="Dedup засеяно" value={migrationResult.dedupSeeded} /></Col>
          </Row>
          {Object.keys(migrationResult.byBookmaker).length > 0 && (
            <><Divider />
              <Text type="secondary">По букмекерам: </Text>
              {Object.entries(migrationResult.byBookmaker).map(([bk, n]) => (
                <Tag key={bk} color="blue" style={{ marginBottom: 4 }}>{BK_LABELS[bk] ?? bk}: {n}</Tag>
              ))}</>
          )}
          <Alert type="info" style={{ marginTop: 16 }}
            message="Новые контроллеры появятся в мониторинге после нажатия «Reschedule All» в разделе Scheduler или перезапуска приложения." />
        </Card>
      )}

      <Modal title="Предварительный просмотр миграции" open={showModal}
        onCancel={() => setShowModal(false)}
        footer={[
          <Button key="cancel" onClick={() => setShowModal(false)}>Отмена</Button>,
          <Button key="run" type="primary" danger icon={<ThunderboltOutlined />}
            loading={executeLoading} onClick={handleExecute}>Выполнить миграцию</Button>,
        ]}>
        {dryRunResult && (
          <>
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={8}><Statistic title="Будет импортировано" value={dryRunResult.toImport} valueStyle={{ color: '#52c41a' }} /></Col>
              <Col span={8}><Statistic title="Дублей (пропустим)" value={dryRunResult.toSkip} /></Col>
              <Col span={8}><Statistic title="Ошибок (пропустим)" value={dryRunResult.toFail}
                valueStyle={dryRunResult.toFail > 0 ? { color: '#faad14' } : undefined} /></Col>
            </Row>
            {Object.keys(dryRunResult.byBookmaker).length > 0 && (
              <><Divider />
                <Text type="secondary">По букмекерам: </Text>
                {Object.entries(dryRunResult.byBookmaker).map(([bk, n]) => (
                  <Tag key={bk} color="blue">{BK_LABELS[bk] ?? bk}: {n}</Tag>
                ))}</>
            )}
            <Alert type="warning" style={{ marginTop: 16 }}
              message="Действие необратимо. Контроллеры будут созданы в БД, dedup засеян в Redis." />
          </>
        )}
      </Modal>
    </div>
  );
}
