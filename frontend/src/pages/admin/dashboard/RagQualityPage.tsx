import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Activity,
  AlertCircle,
  BarChart3,
  Search,
  Shield,
  ThumbsDown,
  ThumbsUp,
  TrendingUp
} from "lucide-react";
import { toast } from "sonner";

import { SimpleLineChart, type TrendSeries } from "@/components/admin/SimpleLineChart";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  getCoverage,
  getRetrievalHitRate,
  getTopQuestions,
  type BlindSpotItem,
  type ChannelHitRateItem,
  type DashboardCoverage,
  type DashboardRetrievalHitRate,
  type DashboardTopQuestions,
  type TopQuestionItem
} from "@/services/dashboardService";

// ============================================================================
// Types
// ============================================================================

type TimeWindow = "24h" | "7d" | "30d";

// ============================================================================
// Constants
// ============================================================================

const WINDOW_OPTIONS: Array<{ value: TimeWindow; label: string }> = [
  { value: "24h", label: "24h" },
  { value: "7d", label: "7d" },
  { value: "30d", label: "30d" }
];

const WINDOW_LABEL_MAP: Record<TimeWindow, string> = {
  "24h": "滚动 24h",
  "7d": "近 7 天",
  "30d": "近 30 天"
};

// ============================================================================
// Utils
// ============================================================================

const formatPercent = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  return `${value.toFixed(1)}%`;
};

const formatNumber = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  return value.toLocaleString("zh-CN");
};

const formatDate = (ts?: number | null) => {
  if (!ts) return "-";
  return new Date(ts).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
};

const HIT_RATE_THRESHOLD = { good: 95, warning: 80 } as const;

const getHitRateTone = (rate?: number | null): "good" | "warning" | "bad" => {
  if (rate === null || rate === undefined) return "warning";
  if (rate >= HIT_RATE_THRESHOLD.good) return "good";
  if (rate >= HIT_RATE_THRESHOLD.warning) return "warning";
  return "bad";
};

const STATUS_COLOR: Record<string, string> = {
  good: "#10B981",
  warning: "#F59E0B",
  bad: "#EF4444"
};

// ============================================================================
// Hooks
// ============================================================================

const useRagQualityData = () => {
  const [timeWindow, setTimeWindow] = useState<TimeWindow>("24h");
  const [hitRate, setHitRate] = useState<DashboardRetrievalHitRate | null>(null);
  const [coverage, setCoverage] = useState<DashboardCoverage | null>(null);
  const [topQuestions, setTopQuestions] = useState<DashboardTopQuestions | null>(null);
  const [loading, setLoading] = useState(true);
  const requestIdRef = useRef(0);

  const loadData = useCallback(async (windowValue: TimeWindow) => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    try {
      const [hr, cov, top] = await Promise.all([
        getRetrievalHitRate(windowValue),
        getCoverage(windowValue),
        getTopQuestions(windowValue, 10)
      ]);
      if (requestIdRef.current !== requestId) return;
      setHitRate(hr);
      setCoverage(cov);
      setTopQuestions(top);
    } catch (err) {
      if (requestIdRef.current !== requestId) return;
      console.error(err);
      toast.error("RAG 质量数据加载失败");
    } finally {
      if (requestIdRef.current !== requestId) return;
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadData(timeWindow);
  }, [loadData, timeWindow]);

  return { timeWindow, setTimeWindow, loading, hitRate, coverage, topQuestions };
};

// ============================================================================
// Base Components
// ============================================================================

const DashCard = ({ children, className }: { children: React.ReactNode; className?: string }) => (
  <div className={cn("rounded-2xl bg-white p-5 shadow-[0_1px_3px_rgba(0,0,0,0.08)]", className)}>
    {children}
  </div>
);

const CardTitle = ({ children }: { children: React.ReactNode }) => (
  <h3 className="mb-4 text-sm font-semibold text-slate-700">{children}</h3>
);

const LoadingBlock = ({ className }: { className?: string }) => (
  <div className={cn("animate-pulse rounded-lg bg-slate-100", className)} />
);

const KpiBadge = ({ value, label, tone }: { value: string; label: string; tone?: string }) => (
  <div className="rounded-xl bg-slate-50 p-4 text-center">
    <p className="text-2xl font-bold tracking-tight text-slate-900">{value}</p>
    <p className="mt-1 text-xs text-slate-500">{label}</p>
  </div>
);

// ============================================================================
// Header
// ============================================================================

const RagQualityHeader = ({
  timeWindow,
  loading,
  onTimeWindowChange
}: {
  timeWindow: TimeWindow;
  loading?: boolean;
  onTimeWindowChange: (w: TimeWindow) => void;
}) => (
  <header className="mb-3 flex items-center justify-between">
    <div>
      <h1 className="text-4xl font-bold tracking-tight text-slate-900">RAG 质量</h1>
      <p className="mt-1 text-sm text-slate-500">检索命中率 · 覆盖率 · 高频问题</p>
    </div>
    <div className="inline-flex rounded-lg bg-white p-1 shadow-sm">
      {WINDOW_OPTIONS.map((opt) => (
        <button
          key={opt.value}
          onClick={() => onTimeWindowChange(opt.value)}
          disabled={loading}
          className={cn(
            "rounded-md px-3 py-1.5 text-sm font-medium transition-all",
            timeWindow === opt.value
              ? "bg-slate-900 text-white"
              : "text-slate-500 hover:text-slate-700"
          )}
        >
          {opt.label}
        </button>
      ))}
    </div>
  </header>
);

// ============================================================================
// 1. Retrieval Hit Rate Section
// ============================================================================

const RetrievalHitRateSection = ({
  hitRate,
  timeWindow,
  loading
}: {
  hitRate: DashboardRetrievalHitRate | null;
  timeWindow: TimeWindow;
  loading?: boolean;
}) => {
  const tone = getHitRateTone(hitRate?.overallHitRate);

  const trendSeries = useMemo<TrendSeries[]>(() => {
    if (!hitRate?.trend?.length) return [];
    return [{ name: "检索命中率", data: hitRate.trend, tone: "primary" }];
  }, [hitRate?.trend]);

  const xAxisMode = timeWindow === "24h" ? "hour" : "date";

  if (loading) {
    return (
      <DashCard>
        <CardTitle>检索命中率</CardTitle>
        <div className="grid gap-4 sm:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <LoadingBlock key={i} className="h-24" />
          ))}
        </div>
      </DashCard>
    );
  }

  return (
    <DashCard>
      <CardTitle>检索命中率</CardTitle>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiBadge
          value={formatPercent(hitRate?.overallHitRate)}
          label="整体命中率"
          tone={tone}
        />
        <KpiBadge
          value={formatNumber(hitRate?.totalRetrievals)}
          label="检索总次数"
        />
        <KpiBadge
          value={formatNumber(hitRate?.successRetrievals)}
          label="成功检索"
        />
        <KpiBadge
          value={hitRate?.channels?.length?.toString() ?? "0"}
          label="检索通道数"
        />
      </div>

      {/* Trend chart */}
      {trendSeries.length > 0 && (
        <div className="mt-5 rounded-xl bg-slate-50 p-4">
          <div className="mb-1 text-xs font-medium text-slate-500">命中率趋势</div>
          <div className="h-48">
            <SimpleLineChart
              series={trendSeries}
              xAxisMode={xAxisMode}
              yAxisType="percent"
              height={192}
              theme="light"
              yAxisTickCount={4}
              thresholds={[
                { value: HIT_RATE_THRESHOLD.good, label: "良好 ≥95%", tone: "info" },
                { value: HIT_RATE_THRESHOLD.warning, label: "警告 <80%", tone: "critical" }
              ]}
            />
          </div>
        </div>
      )}

      {/* Per-channel breakdown */}
      {hitRate?.channels && hitRate.channels.length > 0 && (
        <div className="mt-4 space-y-3">
          <p className="text-xs font-medium text-slate-500">各通道命中率</p>
          {hitRate.channels.map((ch: ChannelHitRateItem) => {
            const chTone = getHitRateTone(ch.hitRate);
            return (
              <div key={ch.channelName} className="flex items-center gap-3">
                <span className="w-36 truncate text-sm text-slate-600">{ch.channelName}</span>
                <div className="flex-1">
                  <div className="h-2 overflow-hidden rounded-full bg-slate-100">
                    <div
                      className="h-full rounded-full transition-all duration-500"
                      style={{
                        width: `${Math.min(100, ch.hitRate)}%`,
                        backgroundColor: STATUS_COLOR[chTone]
                      }}
                    />
                  </div>
                </div>
                <span className="w-16 text-right text-sm font-semibold tabular-nums" style={{ color: STATUS_COLOR[chTone] }}>
                  {formatPercent(ch.hitRate)}
                </span>
                <span className="w-14 text-right text-xs text-slate-400">
                  {ch.success}/{ch.total}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </DashCard>
  );
};

// ============================================================================
// 2. Coverage Analysis Section
// ============================================================================

const CoverageSection = ({
  coverage,
  loading
}: {
  coverage: DashboardCoverage | null;
  loading?: boolean;
}) => {
  const coverageRate = coverage?.coverageRate ?? 0;
  const ringColor = coverageRate >= 90 ? "#10B981" : coverageRate >= 70 ? "#F59E0B" : "#EF4444";
  const radius = 48;
  const circumference = 2 * Math.PI * radius;
  const progress = (Math.min(coverageRate, 100) / 100) * circumference;

  if (loading) {
    return (
      <DashCard>
        <CardTitle>问答覆盖率</CardTitle>
        <div className="grid gap-4 lg:grid-cols-2">
          <LoadingBlock className="h-40" />
          <LoadingBlock className="h-40" />
        </div>
      </DashCard>
    );
  }

  return (
    <DashCard>
      <CardTitle>问答覆盖率</CardTitle>
      <div className="grid gap-6 lg:grid-cols-2">
        {/* Coverage ring + stats */}
        <div>
          <div className="flex items-center gap-6">
            <div className="relative">
              <svg className="-rotate-90" viewBox="0 0 120 120" width="120" height="120">
                <circle cx="60" cy="60" r={radius} fill="none" stroke="#F1F5F9" strokeWidth={8} />
                <circle
                  cx="60"
                  cy="60"
                  r={radius}
                  fill="none"
                  stroke={ringColor}
                  strokeWidth={8}
                  strokeLinecap="round"
                  strokeDasharray={circumference}
                  strokeDashoffset={circumference - progress}
                  className="transition-all duration-700 ease-out"
                />
              </svg>
              <div className="absolute inset-0 flex flex-col items-center justify-center">
                <span className="text-xl font-bold" style={{ color: ringColor }}>
                  {formatPercent(coverageRate)}
                </span>
                <span className="text-xs text-slate-400">覆盖率</span>
              </div>
            </div>

            <div className="space-y-2.5">
              <div className="flex items-center gap-2 text-sm">
                <div className="h-3 w-3 rounded-sm bg-blue-400" />
                <span className="text-slate-600">总提问</span>
                <span className="ml-auto font-semibold tabular-nums">{formatNumber(coverage?.totalQuestions)}</span>
              </div>
              <div className="flex items-center gap-2 text-sm">
                <div className="h-3 w-3 rounded-sm bg-emerald-400" />
                <span className="text-slate-600">已覆盖</span>
                <span className="ml-auto font-semibold tabular-nums">{formatNumber(coverage?.coveredQuestions)}</span>
              </div>
              <div className="flex items-center gap-2 text-sm">
                <div className="h-3 w-3 rounded-sm bg-amber-400" />
                <span className="text-slate-600">无文档</span>
                <span className="ml-auto font-semibold tabular-nums">{formatNumber(coverage?.noDocQuestions)}</span>
              </div>
              <div className="flex items-center gap-2 text-sm">
                <div className="h-3 w-3 rounded-sm bg-red-400" />
                <span className="text-slate-600">检索异常</span>
                <span className="ml-auto font-semibold tabular-nums">{formatNumber(coverage?.retrievalErrorQuestions)}</span>
              </div>
            </div>
          </div>
        </div>

        {/* Blind spots */}
        <div>
          <p className="mb-3 text-xs font-medium text-slate-500">
            知识盲区 TOP {coverage?.blindSpots?.length ?? 0}
          </p>
          {!coverage?.blindSpots?.length ? (
            <p className="text-sm text-slate-400">暂无盲区数据</p>
          ) : (
            <div className="max-h-52 space-y-2 overflow-y-auto pr-1">
              {coverage.blindSpots.map((bs: BlindSpotItem, i: number) => (
                <div key={i} className="flex items-start gap-3 rounded-lg bg-slate-50 p-2.5">
                  <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-md bg-slate-200 text-xs font-medium text-slate-600">
                    {i + 1}
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm text-slate-700">{bs.question}</p>
                    <p className="mt-0.5 flex items-center gap-3 text-xs text-slate-400">
                      <span>频次 {bs.frequency}</span>
                      <span>最近 {formatDate(bs.lastSeen)}</span>
                    </p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </DashCard>
  );
};

// ============================================================================
// 3. Top Questions Section
// ============================================================================

const TopQuestionsSection = ({
  topQuestions,
  loading
}: {
  topQuestions: DashboardTopQuestions | null;
  loading?: boolean;
}) => {
  if (loading) {
    return (
      <DashCard>
        <CardTitle>高频问题排行</CardTitle>
        <LoadingBlock className="h-64" />
      </DashCard>
    );
  }

  const items = topQuestions?.items ?? [];

  return (
    <DashCard>
      <CardTitle>高频问题排行</CardTitle>
      {items.length === 0 ? (
        <p className="py-8 text-center text-sm text-slate-400">暂无数据</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-left text-xs font-medium uppercase text-slate-400">
                <th className="w-10 py-2 pr-2 text-center">#</th>
                <th className="py-2">问题</th>
                <th className="w-16 py-2 text-right">频次</th>
                <th className="w-20 py-2 text-right">命中率</th>
                <th className="w-20 py-2 text-right">点赞率</th>
                <th className="w-16 py-2 text-right">反馈数</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item: TopQuestionItem, i: number) => {
                const hitTone = getHitRateTone(item.hitRate);
                const thumbsTone = item.thumbsUpRate >= 70 ? "good" : item.thumbsUpRate >= 40 ? "warning" : "bad";
                return (
                  <tr
                    key={i}
                    className="border-b border-slate-50 transition-colors hover:bg-slate-50"
                  >
                    <td className="py-2.5 pr-2 text-center text-xs font-medium text-slate-400">
                      {i + 1}
                    </td>
                    <td className="max-w-64 py-2.5">
                      <p className="truncate font-medium text-slate-700">{item.question}</p>
                    </td>
                    <td className="py-2.5 text-right font-semibold tabular-nums text-slate-700">
                      {item.count}
                    </td>
                    <td
                      className="py-2.5 text-right font-semibold tabular-nums"
                      style={{ color: STATUS_COLOR[hitTone] }}
                    >
                      {formatPercent(item.hitRate)}
                    </td>
                    <td
                      className="py-2.5 text-right font-semibold tabular-nums"
                      style={{ color: STATUS_COLOR[thumbsTone] }}
                    >
                      {item.feedbackCount === 0 ? "-" : formatPercent(item.thumbsUpRate)}
                    </td>
                    <td className="py-2.5 text-right tabular-nums text-slate-500">
                      {item.feedbackCount}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </DashCard>
  );
};

// ============================================================================
// Main Page
// ============================================================================

export function RagQualityPage() {
  const { timeWindow, setTimeWindow, loading, hitRate, coverage, topQuestions } =
    useRagQualityData();

  return (
    <div className="admin-page space-y-5">
      <RagQualityHeader
        timeWindow={timeWindow}
        loading={loading}
        onTimeWindowChange={setTimeWindow}
      />

      <div className="space-y-5">
        <RetrievalHitRateSection hitRate={hitRate} timeWindow={timeWindow} loading={loading} />
        <CoverageSection coverage={coverage} loading={loading} />
        <TopQuestionsSection topQuestions={topQuestions} loading={loading} />
      </div>
    </div>
  );
}
