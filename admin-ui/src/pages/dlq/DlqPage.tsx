import { Card, Button, Space, Typography, Tag, Statistic, Row, Col, App } from 'antd';
import { ReloadOutlined, PlayCircleOutlined, CheckCircleOutlined, WarningOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { dlqApi } from '../../api/endpoints';

function statusTag(status: string) {
  if (status === 'OK') return <Tag icon={<CheckCircleOutlined />} color="success">OK</Tag>;
  if (status === 'WARNING') return <Tag icon={<WarningOutlined />} color="warning">WARNING</Tag>;
  return <Tag icon={<CloseCircleOutlined />} color="error">{status}</Tag>;
}

export default function DlqPage() {
  const { notification, modal } = App.useApp();
  const queryClient = useQueryClient();

  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['dlq-stats'],
    queryFn: dlqApi.stats,
    refetchInterval: 30_000,
  });

  const replayMutation = useMutation({
    mutationFn: dlqApi.replay,
    onSuccess: (result) => {
      notification.success({ message: `Replay complete — ${result.replayed} messages requeued` });
      queryClient.invalidateQueries({ queryKey: ['dlq-stats'] });
    },
    onError: (err: Error) => notification.error({ message: 'Replay failed', description: err.message }),
  });

  const confirmReplay = () => {
    modal.confirm({
      title: 'Replay DLQ?',
      content: 'All messages from dlq.final will be requeued for processing.',
      okText: 'Replay',
      okType: 'primary',
      onOk: () => replayMutation.mutate(),
    });
  };

  return (
    <>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ReloadOutlined spin={isFetching} />} onClick={() => refetch()} loading={isFetching}>
          Refresh
        </Button>
        <Button
          type="primary"
          danger={data?.dlqFinalCount ? data.dlqFinalCount > 0 : false}
          icon={<PlayCircleOutlined />}
          onClick={confirmReplay}
          loading={replayMutation.isPending}
          disabled={isLoading}
        >
          Replay DLQ
        </Button>
      </Space>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={8}>
          <Card loading={isLoading}>
            <Statistic
              title="Messages in dlq.final"
              value={data?.dlqFinalCount ?? 0}
              valueStyle={{ color: data?.dlqFinalCount ? '#ff4d4f' : '#52c41a' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card loading={isLoading}>
            <Statistic title="Total Replayed" value={data?.replayed ?? 0} />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card loading={isLoading}>
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>Status</Typography.Text>
              <div style={{ marginTop: 8 }}>
                {data ? statusTag(data.status) : '—'}
              </div>
            </div>
          </Card>
        </Col>
      </Row>
    </>
  );
}
