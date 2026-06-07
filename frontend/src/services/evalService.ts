import { api } from "@/services/api";

// ============================================================================
// Types
// ============================================================================

export type EvalOverview = {
  avgFaithfulness: number | null;
  avgAnswerRelevancy: number | null;
  avgCorrectness: number | null;
  lowScoreCount: number;
  totalEvalCount: number;
  lowScoreRatio: number | null;
};

export type EvalTrendPoint = {
  time: string;
  avgScore: number | null;
  totalCount: number;
  failCount: number;
};

export type LowScoreSample = {
  id: string;
  traceId: string;
  conversationId: string;
  messageId: string;
  question: string;
  answer: string;
  metricName: string;
  score: number;
  label: string;
  reason: string;
  createTime: string;
};

// ============================================================================
// API functions
// ============================================================================

export async function getEvalOverview(window: string = "7d"): Promise<EvalOverview> {
  return api.get<EvalOverview, EvalOverview>("/admin/eval/overview", {
    params: { window }
  });
}

export async function getEvalTrends(
  metric: string,
  window: string = "7d",
  granularity: string = "1d"
): Promise<EvalTrendPoint[]> {
  return api.get<EvalTrendPoint[], EvalTrendPoint[]>("/admin/eval/trends", {
    params: { metric, window, granularity }
  });
}

export async function getLowScoreSamples(
  metric: string = "faithfulness",
  limit: number = 50
): Promise<LowScoreSample[]> {
  return api.get<LowScoreSample[], LowScoreSample[]>("/admin/eval/low-score-samples", {
    params: { metric, limit }
  });
}
