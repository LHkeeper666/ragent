import { api } from "@/services/api";

export type DashboardKpi = {
  value: number;
  delta?: number;
  deltaPct?: number;
};

export type DashboardOverview = {
  window: string;
  compareWindow: string;
  updatedAt: number;
  kpis: {
    totalUsers: DashboardKpi;
    activeUsers: DashboardKpi;
    totalSessions: DashboardKpi;
    sessions24h: DashboardKpi;
    totalMessages: DashboardKpi;
    messages24h: DashboardKpi;
  };
};

export type DashboardPerformance = {
  window: string;
  avgLatencyMs: number;
  p95LatencyMs: number;
  successRate: number;
  errorRate: number;
  noDocRate: number;
  slowRate: number;
};

export type DashboardTrendPoint = {
  ts: number;
  value: number;
};

export type DashboardTrendSeries = {
  name: string;
  data: DashboardTrendPoint[];
};

export type DashboardTrends = {
  metric: string;
  window: string;
  granularity: string;
  series: DashboardTrendSeries[];
};

export async function getDashboardOverview(window: string = "24h"): Promise<DashboardOverview> {
  return api.get<DashboardOverview, DashboardOverview>("/admin/dashboard/overview", {
    params: { window }
  });
}

export async function getDashboardPerformance(window: string = "24h"): Promise<DashboardPerformance> {
  return api.get<DashboardPerformance, DashboardPerformance>("/admin/dashboard/performance", {
    params: { window }
  });
}

export async function getDashboardTrends(
  metric: string,
  window: string = "7d",
  granularity: string = "day"
): Promise<DashboardTrends> {
  return api.get<DashboardTrends, DashboardTrends>("/admin/dashboard/trends", {
    params: { metric, window, granularity }
  });
}

// ============================================================
// RAG Quality Dashboard
// ============================================================

export type ChannelHitRateItem = {
  channelName: string;
  total: number;
  success: number;
  error: number;
  hitRate: number;
};

export type DashboardRetrievalHitRate = {
  window: string;
  overallHitRate: number;
  totalRetrievals: number;
  successRetrievals: number;
  channels: ChannelHitRateItem[];
  trend: DashboardTrendPoint[];
};

export type BlindSpotItem = {
  question: string;
  frequency: number;
  lastSeen: number;
};

export type DashboardCoverage = {
  window: string;
  totalQuestions: number;
  coveredQuestions: number;
  noDocQuestions: number;
  retrievalErrorQuestions: number;
  coverageRate: number;
  blindSpots: BlindSpotItem[];
};

export type TopQuestionItem = {
  question: string;
  count: number;
  hitRate: number;
  thumbsUpRate: number;
  feedbackCount: number;
};

export type DashboardTopQuestions = {
  window: string;
  items: TopQuestionItem[];
};

export async function getRetrievalHitRate(
  window: string = "24h"
): Promise<DashboardRetrievalHitRate> {
  return api.get<DashboardRetrievalHitRate, DashboardRetrievalHitRate>(
    "/admin/dashboard/retrieval-hit-rate",
    { params: { window } }
  );
}

export async function getCoverage(
  window: string = "24h"
): Promise<DashboardCoverage> {
  return api.get<DashboardCoverage, DashboardCoverage>(
    "/admin/dashboard/coverage",
    { params: { window } }
  );
}

export async function getTopQuestions(
  window: string = "24h",
  limit: number = 10
): Promise<DashboardTopQuestions> {
  return api.get<DashboardTopQuestions, DashboardTopQuestions>(
    "/admin/dashboard/top-questions",
    { params: { window, limit } }
  );
}
