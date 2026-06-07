import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  BarChart3,
  CheckCircle2,
  RefreshCw,
  Shield,
  Target,
  ThumbsDown,
  TrendingUp,
  XCircle
} from "lucide-react";
import { toast } from "sonner";

import { SimpleLineChart, type TrendSeries } from "@/components/admin/SimpleLineChart";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  getEvalOverview,
  getEvalTrends,
  getLowScoreSamples,
  type EvalOverview,
  type EvalTrendPoint,
  type LowScoreSample
} from "@/services/evalService";

// ============================================================================
// Types
// ============================================================================

type TimeWindow = "24h" | "7d" | "30d";

interface EvalPageData {
  overview: EvalOverview | null;
  faithfulnessTrend: EvalTrendPoint[];
  relevancyTrend: EvalTrendPoint[];
  correctnessTrend: EvalTrendPoint[];
  lowScoreSamples: LowScoreSample[];
}

// ============================================================================
// Constants
// ============================================================================

const WINDOW_OPTIONS: Array<{ value: TimeWindow; label: string }> = [
  { value: "24h", label: "近 24 小时" },
  { value: "7d", label: "近 7 天" },
  { value: "30d", label: "近 30 天" }
];

const METRIC_OPTIONS = [
  { value: "faithfulness", label: "忠实度" },
  { value: "answer_relevancy", label: "相关性" },
  { value: "correctness", label: "正确性" }
];

const METRIC_LABEL_MAP: Record<string, string> = {
  faithfulness: "忠实度",
  answer_relevancy: "相关性",
  correctness: "正确性"
};

const PASS_THRESHOLD = 0.7;
const WARN_THRESHOLD = 0.4;

// ============================================================================
// Utils
// ============================================================================

const formatScore = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  return value.toFixed(2);
};

const formatPercent = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  return `${(value * 100).toFixed(1)}%`;
};

const formatNumber = (value?: number | null) => {
  if (value === null || value === undefined) return "-";
  return value.toLocaleString("zh-CN");
};

const getScoreTone = (score?: number | null): "good" | "warning" | "bad" => {
  if (score === null || score === undefined) return "bad";
  if (score >= PASS_THRESHOLD) return "good";
  if (score >= WARN_THRESHOLD) return "warning";
  return "bad";
};

const getScoreLabel = (score?: number | null): string => {
  if (score === null || score === undefined) return "无数据";
  if (score >= PASS_THRESHOLD) return "PASS";
  if (score >= WARN_THRESHOLD) return "WARN";
  return "FAIL";
};

const getScoreBadgeClass = (label?: string): string => {
  switch (label) {
    case "PASS": return "bg-emerald-100 text-emerald-700";
    case "WARN": return "bg-amber-100 text-amber-700";
    case "FAIL": return "bg-red-100 text-red-700";
    default: return "bg-slate-100 text-slate-600";
  }
};

const formatDateTime = (value?: string | null) => {
  if (!value) return "-";
  try {
    const d = new Date(value);
    return d.toLocaleString("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit"
    });
  } catch {
    return value;
  }
};

const truncateText = (text?: string, maxLen: number = 200) => {
  if (!text) return "-";
  return text.length <= maxLen ? text : text.substring(0, maxLen) + "...";
};

// ============================================================================
// Sub-components
// ============================================================================

function LoadingBlock() {
  return (
    <div className="flex h-40 items-center justify-center rounded-xl border border-slate-200 bg-white">
      <RefreshCw className="h-6 w-6 animate-spin text-slate-300" />
    </div>
  );
}

function DashCard({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div className={cn("rounded-xl border border-slate-200 bg-white p-5 shadow-sm", className)}>
      {children}
    </div>
  );
}

function CardTitle({ icon: Icon, children }: { icon: React.ElementType; children: React.ReactNode }) {
  return (
    <div className="mb-4 flex items-center gap-2 text-sm font-semibold text-slate-700">
      <Icon className="h-4 w-4 text-slate-400" />
      <span>{children}</span>
    </div>
  );
}

function ScoreKpiCard({
  title,
  icon: Icon,
  score,
  totalCount,
  tone
}: {
  title: string;
  icon: React.ElementType;
  score: number | null;
  totalCount: number;
  tone: "good" | "warning" | "bad";
}) {
  const toneColors = {
    good: "border-emerald-200 bg-emerald-50/50",
    warning: "border-amber-200 bg-amber-50/50",
    bad: "border-red-200 bg-red-50/50"
  };

  const toneIcons = {
    good: CheckCircle2,
    warning: AlertTriangle,
    bad: XCircle
  };

  const ToneIcon = toneIcons[tone];

  return (
    <div className={cn("rounded-xl border p-5 shadow-sm", toneColors[tone])}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm font-medium text-slate-600">
          <Icon className="h-4 w-4 text-slate-400" />
          <span>{title}</span>
        </div>
        <ToneIcon
          className={cn(
            "h-5 w-5",
            tone === "good" && "text-emerald-500",
            tone === "warning" && "text-amber-500",
            tone === "bad" && "text-red-500"
          )}
        />
      </div>
      <div className="mt-3 flex items-baseline gap-2">
        <span className={cn(
          "text-3xl font-bold",
          tone === "good" && "text-emerald-700",
          tone === "warning" && "text-amber-700",
          tone === "bad" && "text-red-700"
        )}>
          {formatScore(score)}
        </span>
        <span className={cn(
          "rounded-full px-2 py-0.5 text-xs font-semibold",
          getScoreBadgeClass(getScoreLabel(score))
        )}>
          {getScoreLabel(score)}
        </span>
      </div>
      <p className="mt-1 text-xs text-slate-400">
        评估样本数: {formatNumber(totalCount)}
      </p>
    </div>
  );
}

function LowScoreRatioCard({
  ratio,
  lowScoreCount,
  totalCount
}: {
  ratio: number | null;
  lowScoreCount: number;
  totalCount: number;
}) {
  const pct = ratio !== null ? Math.round(ratio * 100) : null;
  const tone = pct !== null ? (pct <= 10 ? "good" : pct <= 30 ? "warning" : "bad") : "bad";

  const toneStyles = {
    good: "border-emerald-200 bg-emerald-50/50 text-emerald-700",
    warning: "border-amber-200 bg-amber-50/50 text-amber-700",
    bad: "border-red-200 bg-red-50/50 text-red-700"
  };

  return (
    <div className={cn("rounded-xl border p-5 shadow-sm", toneStyles[tone])}>
      <div className="flex items-center gap-2 text-sm font-medium">
        <ThumbsDown className="h-4 w-4" />
        <span>低分样本比例</span>
      </div>
      <div className="mt-3">
        <span className="text-3xl font-bold">{pct !== null ? `${pct}%` : "-"}</span>
      </div>
      <p className="mt-1 text-xs opacity-70">
        {lowScoreCount} / {totalCount} 个样本
      </p>
    </div>
  );
}

function TrendChartSection({
  metric,
  label,
  data,
  loading
}: {
  metric: string;
  label: string;
  data: EvalTrendPoint[];
  loading: boolean;
}) {
  if (loading) {
    return (
      <DashCard>
        <CardTitle icon={TrendingUp}>{label}趋势</CardTitle>
        <LoadingBlock />
      </DashCard>
    );
  }

  if (!data || data.length === 0) {
    return (
      <DashCard>
        <CardTitle icon={TrendingUp}>{label}趋势</CardTitle>
        <div className="flex h-40 items-center justify-center text-sm text-slate-400">
          暂无评测数据
        </div>
      </DashCard>
    );
  }

  const series: TrendSeries[] = [
    {
      name: "平均分",
      data: data.map((pt) => ({ ts: new Date(pt.time).getTime(), value: pt.avgScore ?? 0 })),
      tone: "primary"
    },
    {
      name: "FAIL 数量",
      data: data.map((pt) => ({ ts: new Date(pt.time).getTime(), value: pt.failCount })),
      tone: "danger",
      lineStyle: "dashed"
    }
  ];

  return (
    <DashCard>
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm font-semibold text-slate-700">
          <TrendingUp className="h-4 w-4 text-slate-400" />
          <span>{label}趋势</span>
        </div>
        <div className="flex items-center gap-4 text-xs text-slate-500">
          <span className="flex items-center gap-1">
            <span className="inline-block h-2.5 w-2.5 rounded-full bg-violet-400" />
            平均分
          </span>
          <span className="flex items-center gap-1">
            <span className="inline-block h-2.5 w-2.5 rounded-full" style={{ background: "#ef4444" }} />
            FAIL 数
          </span>
        </div>
      </div>
      <SimpleLineChart
        series={series}
        height={220}
        yAxisType="number"
        xAxisMode="date"
        thresholds={[
          { value: PASS_THRESHOLD, label: "PASS (0.7)", tone: "info" },
          { value: WARN_THRESHOLD, label: "WARN (0.4)", tone: "warning" }
        ]}
      />
      <div className="mt-2 grid grid-cols-3 gap-4 text-center text-xs text-slate-500">
        <div>
          <span className="block text-lg font-semibold text-slate-700">
            {formatScore(data.length > 0
              ? data.reduce((sum, p) => sum + (p.avgScore ?? 0), 0) / data.length
              : null)}
          </span>
          期间均分
        </div>
        <div>
          <span className="block text-lg font-semibold text-slate-700">
            {formatNumber(data.reduce((sum, p) => sum + p.totalCount, 0))}
          </span>
          期间评测数
        </div>
        <div>
          <span className="block text-lg font-semibold text-slate-700">
            {formatNumber(data.reduce((sum, p) => sum + p.failCount, 0))}
          </span>
          期间 FAIL 数
        </div>
      </div>
    </DashCard>
  );
}

function LowScoreSamplesTable({
  metric,
  data,
  loading
}: {
  metric: string;
  data: LowScoreSample[];
  loading: boolean;
}) {
  return (
    <DashCard>
      <CardTitle icon={ThumbsDown}>
        {METRIC_LABEL_MAP[metric] || metric} · 低分样本
      </CardTitle>

      {loading ? (
        <LoadingBlock />
      ) : data.length === 0 ? (
        <div className="flex h-32 items-center justify-center text-sm text-slate-400">
          暂无低分样本
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left text-xs font-medium uppercase text-slate-500">
                <th className="whitespace-nowrap px-3 py-2.5">分数</th>
                <th className="whitespace-nowrap px-3 py-2.5">标签</th>
                <th className="whitespace-nowrap px-3 py-2.5">问题</th>
                <th className="whitespace-nowrap px-3 py-2.5">回答</th>
                <th className="whitespace-nowrap px-3 py-2.5">评测理由</th>
                <th className="whitespace-nowrap px-3 py-2.5">时间</th>
              </tr>
            </thead>
            <tbody>
              {data.map((sample) => (
                <tr key={sample.id} className="border-b border-slate-100 hover:bg-slate-50">
                  <td className="px-3 py-2.5 font-mono font-semibold">
                    <span className={cn(
                      sample.score >= PASS_THRESHOLD ? "text-emerald-600" :
                      sample.score >= WARN_THRESHOLD ? "text-amber-600" :
                      "text-red-600"
                    )}>
                      {formatScore(sample.score)}
                    </span>
                  </td>
                  <td className="px-3 py-2.5">
                    <span className={cn("rounded-full px-2 py-0.5 text-xs font-semibold", getScoreBadgeClass(sample.label))}>
                      {sample.label}
                    </span>
                  </td>
                  <td className="max-w-[240px] px-3 py-2.5 text-slate-700" title={sample.question}>
                    {truncateText(sample.question, 80)}
                  </td>
                  <td className="max-w-[280px] px-3 py-2.5 text-slate-600" title={sample.answer}>
                    {truncateText(sample.answer, 120)}
                  </td>
                  <td className="max-w-[200px] px-3 py-2.5 text-xs text-slate-500" title={sample.reason}>
                    {truncateText(sample.reason, 60)}
                  </td>
                  <td className="whitespace-nowrap px-3 py-2.5 text-xs text-slate-400">
                    {formatDateTime(sample.createTime)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </DashCard>
  );
}

// ============================================================================
// Data Hook
// ============================================================================

function useEvalData(window: TimeWindow) {
  const [data, setData] = useState<EvalPageData>({
    overview: null,
    faithfulnessTrend: [],
    relevancyTrend: [],
    correctnessTrend: [],
    lowScoreSamples: []
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const requestIdRef = useRef(0);

  const load = useCallback(async (w: TimeWindow) => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setError(null);

    try {
      const [overview, faithTrend, relevTrend, corrTrend, samples] = await Promise.all([
        getEvalOverview(w),
        getEvalTrends("faithfulness", w, "1d"),
        getEvalTrends("answer_relevancy", w, "1d"),
        getEvalTrends("correctness", w, "1d"),
        getLowScoreSamples("faithfulness", 50)
      ]);

      if (requestId !== requestIdRef.current) return;

      setData({
        overview,
        faithfulnessTrend: faithTrend,
        relevancyTrend: relevTrend,
        correctnessTrend: corrTrend,
        lowScoreSamples: samples
      });
      setLastUpdated(Date.now());
    } catch (err) {
      if (requestId !== requestIdRef.current) return;
      const message = (err as Error)?.message || "加载评测数据失败";
      setError(message);
      toast.error(message);
    } finally {
      if (requestId === requestIdRef.current) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    load(window);
  }, [window, load]);

  const refresh = useCallback(() => {
    load(window);
  }, [window, load]);

  return { ...data, loading, error, lastUpdated, refresh };
}

// ============================================================================
// Page
// ============================================================================

export function EvalPage() {
  const [timeWindow, setTimeWindow] = useState<TimeWindow>("7d");
  const [lowScoreMetric, setLowScoreMetric] = useState("faithfulness");
  const {
    overview,
    faithfulnessTrend,
    relevancyTrend,
    correctnessTrend,
    lowScoreSamples,
    loading,
    lastUpdated,
    refresh
  } = useEvalData(timeWindow);

  return (
    <div className="admin-page space-y-6 p-6">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">RAG 效果评测</h1>
          <p className="mt-1 text-sm text-slate-500">
            基于 LLM Judge 的答案质量自动评估：忠实度 · 相关性 · 正确性
            {lastUpdated && (
              <span className="ml-2 text-xs text-slate-400">
                更新于 {new Date(lastUpdated).toLocaleTimeString("zh-CN")}
              </span>
            )}
          </p>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex rounded-lg border border-slate-200 bg-white p-0.5">
            {WINDOW_OPTIONS.map((opt) => (
              <button
                key={opt.value}
                type="button"
                onClick={() => setTimeWindow(opt.value)}
                className={cn(
                  "rounded-md px-3 py-1.5 text-xs font-medium transition-colors",
                  timeWindow === opt.value
                    ? "bg-violet-600 text-white shadow-sm"
                    : "text-slate-600 hover:text-slate-900"
                )}
              >
                {opt.label}
              </button>
            ))}
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={refresh}
            disabled={loading}
            className="gap-1.5"
          >
            <RefreshCw className={cn("h-4 w-4", loading && "animate-spin")} />
            刷新
          </Button>
        </div>
      </div>

      {/* Error */}
      {!loading && !overview && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 bg-white py-16">
          <BarChart3 className="mb-3 h-10 w-10 text-slate-300" />
          <p className="text-sm font-medium text-slate-500">暂无评测数据</p>
          <p className="mt-1 text-xs text-slate-400">
            开启 rag.eval.enabled 后，系统将自动对线上对话进行抽样评测
          </p>
        </div>
      )}

      {/* KPIs */}
      {overview && (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <ScoreKpiCard
              title="忠实度 (Faithfulness)"
              icon={Shield}
              score={overview.avgFaithfulness}
              totalCount={overview.totalEvalCount}
              tone={getScoreTone(overview.avgFaithfulness)}
            />
            <ScoreKpiCard
              title="相关性 (Relevancy)"
              icon={Target}
              score={overview.avgAnswerRelevancy}
              totalCount={overview.totalEvalCount}
              tone={getScoreTone(overview.avgAnswerRelevancy)}
            />
            <ScoreKpiCard
              title="正确性 (Correctness)"
              icon={CheckCircle2}
              score={overview.avgCorrectness}
              totalCount={overview.totalEvalCount}
              tone={getScoreTone(overview.avgCorrectness)}
            />
            <LowScoreRatioCard
              ratio={overview.lowScoreRatio}
              lowScoreCount={overview.lowScoreCount}
              totalCount={overview.totalEvalCount}
            />
          </div>

          {/* Trend Charts */}
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <TrendChartSection
              metric="faithfulness"
              label="忠实度"
              data={faithfulnessTrend}
              loading={loading}
            />
            <TrendChartSection
              metric="answer_relevancy"
              label="相关性"
              data={relevancyTrend}
              loading={loading}
            />
            <TrendChartSection
              metric="correctness"
              label="正确性"
              data={correctnessTrend}
              loading={loading}
            />
          </div>

          {/* Low Score Samples */}
          <div>
            <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
              <h2 className="text-lg font-bold text-slate-900">低分样本分析</h2>
              <div className="flex rounded-lg border border-slate-200 bg-white p-0.5">
                {METRIC_OPTIONS.map((opt) => (
                  <button
                    key={opt.value}
                    type="button"
                    onClick={() => setLowScoreMetric(opt.value)}
                    className={cn(
                      "rounded-md px-3 py-1.5 text-xs font-medium transition-colors",
                      lowScoreMetric === opt.value
                        ? "bg-violet-600 text-white shadow-sm"
                        : "text-slate-600 hover:text-slate-900"
                    )}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            </div>
            <LowScoreSamplesTable
              metric={lowScoreMetric}
              data={lowScoreSamples}
              loading={loading}
            />
          </div>
        </>
      )}
    </div>
  );
}
