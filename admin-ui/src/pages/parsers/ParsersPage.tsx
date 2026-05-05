import { useState, useEffect, useRef } from 'react';
import {
  Card,
  Row,
  Col,
  Button,
  Progress,
  Space,
  Tag,
  Modal,
  Form,
  Input,
  Typography,
  List,
  Spin,
  App,
} from 'antd';
import { ReloadOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { useQuery, useMutation } from '@tanstack/react-query';
import { parsersApi } from '../../api/endpoints';
import { IndicatorBadge } from '../../components/StatusBadge';
import type { BookmakerStatus, TestParseResult } from '../../api/types';

const REFRESH_INTERVAL = 30;

function indicatorBorderColor(indicator: BookmakerStatus['indicator']): string {
  switch (indicator) {
    case 'green': return '#52c41a';
    case 'yellow': return '#faad14';
    case 'red': return '#ff4d4f';
  }
}

function indicatorProgressColor(indicator: BookmakerStatus['indicator']): string {
  switch (indicator) {
    case 'green': return '#52c41a';
    case 'yellow': return '#faad14';
    case 'red': return '#ff4d4f';
  }
}

export default function ParsersPage() {
  const [countdown, setCountdown] = useState(REFRESH_INTERVAL);
  const [testBookmaker, setTestBookmaker] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<TestParseResult | null>(null);
  const [testForm] = Form.useForm<{ url: string }>();
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const { notification } = App.useApp();

  const { data: parsers, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['parsers'],
    queryFn: parsersApi.list,
    refetchInterval: REFRESH_INTERVAL * 1000,
  });

  // Countdown timer
  useEffect(() => {
    setCountdown(REFRESH_INTERVAL);
    intervalRef.current = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          return REFRESH_INTERVAL;
        }
        return prev - 1;
      });
    }, 1000);
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, []);

  const [, setPollResults] = useState<Record<string, { success: boolean; count: number; ms: number }>>({});

  const testMutation = useMutation({
    mutationFn: ({ bookmaker, url }: { bookmaker: string; url: string }) =>
      parsersApi.test(bookmaker, { url }),
    onSuccess: (result) => {
      setTestResult(result);
    },
    onError: (err: Error) => {
      notification.error({ message: 'Test failed', description: err.message });
    },
  });

  const pollMutation = useMutation({
    mutationFn: (bookmaker: string) => parsersApi.poll(bookmaker),
    onSuccess: (result, bookmaker) => {
      setPollResults((prev) => ({
        ...prev,
        [bookmaker]: { success: result.success, count: result.eventCount, ms: result.latencyMs },
      }));
      notification.success({
        message: `Poll ${bookmaker}: ${result.success ? `${result.eventCount} sports, ${result.latencyMs}ms` : result.errorMessage}`,
      });
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  const handleManualRefresh = () => {
    setCountdown(REFRESH_INTERVAL);
    refetch();
  };

  const openTestModal = (bookmaker: string) => {
    setTestBookmaker(bookmaker);
    setTestResult(null);
    testForm.resetFields();
  };

  const closeTestModal = () => {
    setTestBookmaker(null);
    setTestResult(null);
    testForm.resetFields();
  };

  return (
    <>
      <Space style={{ marginBottom: 16 }} size="middle">
        <Button
          icon={<ReloadOutlined spin={isFetching} />}
          onClick={handleManualRefresh}
          loading={isFetching}
        >
          Refresh
        </Button>
        <Typography.Text type="secondary">
          Auto-refresh in{' '}
          <Typography.Text strong style={{ color: countdown <= 5 ? '#ff4d4f' : undefined }}>
            {countdown}s
          </Typography.Text>
        </Typography.Text>
        <Progress
          percent={Math.round(((REFRESH_INTERVAL - countdown) / REFRESH_INTERVAL) * 100)}
          showInfo={false}
          style={{ width: 100 }}
          strokeColor="#1668dc"
          size="small"
        />
      </Space>

      {isLoading ? (
        <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 64 }}>
          <Spin size="large" />
        </div>
      ) : (
        <Row gutter={[16, 16]}>
          {(parsers ?? []).map((parser: BookmakerStatus) => (
            <Col key={parser.bookmaker} xs={24} sm={12} lg={8} xl={6}>
              <Card
                title={
                  <Space>
                    <span>{parser.bookmaker}</span>
                    <IndicatorBadge indicator={parser.indicator} label={parser.cbState} />
                  </Space>
                }
                extra={
                  <Space size={4}>
                    <Button
                      size="small"
                      icon={<PlayCircleOutlined />}
                      onClick={() => pollMutation.mutate(parser.bookmaker)}
                      type="default"
                      loading={pollMutation.isPending}
                    >
                      Poll
                    </Button>
                    <Button
                      size="small"
                      icon={<PlayCircleOutlined />}
                      onClick={() => openTestModal(parser.bookmaker)}
                      type="link"
                    >
                      Test
                    </Button>
                  </Space>
                }
                style={{
                  borderLeft: `4px solid ${indicatorBorderColor(parser.indicator)}`,
                }}
                size="small"
              >
                <Space direction="vertical" style={{ width: '100%' }} size="small">
                  <div>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      Success Rate
                    </Typography.Text>
                    <Progress
                      percent={Math.round(parser.successRate * 100)}
                      strokeColor={indicatorProgressColor(parser.indicator)}
                      size="small"
                      format={(p) => `${p}%`}
                    />
                  </div>
                  <Space size="large">
                    <div>
                      <Typography.Text type="secondary" style={{ fontSize: 11 }}>OK</Typography.Text>
                      <br />
                      <Tag color="success">{parser.successfulCalls}</Tag>
                    </div>
                    <div>
                      <Typography.Text type="secondary" style={{ fontSize: 11 }}>FAIL</Typography.Text>
                      <br />
                      <Tag color="error">{parser.failedCalls}</Tag>
                    </div>
                    <div>
                      <Typography.Text type="secondary" style={{ fontSize: 11 }}>BLOCKED</Typography.Text>
                      <br />
                      <Tag color="warning">{parser.notPermittedCalls}</Tag>
                    </div>
                  </Space>
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
      )}

      <Modal
        title={`Test Parser: ${testBookmaker}`}
        open={testBookmaker !== null}
        onCancel={closeTestModal}
        footer={null}
        width={600}
      >
        <Form<{ url: string }>
          form={testForm}
          layout="vertical"
          onFinish={(values) => {
            if (testBookmaker) {
              testMutation.mutate({ bookmaker: testBookmaker, url: values.url });
            }
          }}
        >
          <Form.Item
            name="url"
            label="URL to parse"
            rules={[
              { required: true, message: 'Enter a URL' },
              { type: 'url', message: 'Enter a valid URL' },
            ]}
          >
            <Input placeholder="https://..." />
          </Form.Item>
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              loading={testMutation.isPending}
              icon={<PlayCircleOutlined />}
            >
              Run Test
            </Button>
          </Form.Item>
        </Form>

        {testResult && (
          <Card
            size="small"
            title={
              <Space>
                <Tag color={testResult.success ? 'success' : 'error'}>
                  {testResult.success ? 'SUCCESS' : 'FAILED'}
                </Tag>
                <Typography.Text>
                  {testResult.eventCount} events • {testResult.latencyMs}ms
                </Typography.Text>
              </Space>
            }
          >
            {testResult.errorMessage && (
              <Typography.Text type="danger" style={{ display: 'block', marginBottom: 8 }}>
                Error: {testResult.errorMessage}
              </Typography.Text>
            )}
            {testResult.sample.length > 0 && (
              <>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Sample events:
                </Typography.Text>
                <List
                  size="small"
                  dataSource={testResult.sample}
                  renderItem={(item) => (
                    <List.Item>
                      <Typography.Text style={{ fontSize: 12 }}>{item}</Typography.Text>
                    </List.Item>
                  )}
                  style={{ maxHeight: 200, overflowY: 'auto' }}
                />
              </>
            )}
          </Card>
        )}
      </Modal>
    </>
  );
}
