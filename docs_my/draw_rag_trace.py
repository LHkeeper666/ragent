import matplotlib
matplotlib.rcParams["font.family"] = ["SimHei", "Microsoft YaHei", "sans-serif"]
matplotlib.rcParams["axes.unicode_minus"] = False
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

fig, ax = plt.subplots(1, 1, figsize=(14, 10))
ax.set_xlim(0, 14)
ax.set_ylim(0, 15)
ax.axis("off")

# 配色
C_QUERY  = "#1565c0"  # 用户输入
C_REWRITE = "#6a1b9a"
C_INTENT = "#6a1b9a"
C_VECTOR = "#00838f"
C_KW     = "#e65100"
C_DEDUP  = "#2e7d32"
C_FUSION = "#2e7d32"
C_RERANK = "#2e7d32"
C_CTX    = "#37474f"
C_LLM    = "#c62828"
C_SSE    = "#ad1457"
C_BRANCH_BG = "#f5f5f5"

# ==================== 第1层：用户输入 ====================
y0 = 14.2
box_w, box_h = 5.6, 0.7
x_center = 4.3

ax.add_patch(FancyBboxPatch((x_center, y0 - box_h/2), box_w, box_h,
    boxstyle="round,pad=0.08", facecolor=C_QUERY, edgecolor="white", linewidth=1.5))
ax.text(x_center + box_w/2, y0, "用户输入", ha="center", va="center",
        fontsize=10, fontweight="bold", color="white")
ax.text(x_center + box_w/2, y0 - 0.75, "使用PyTorch搭建CNN的关键步骤有哪些",
        ha="center", va="top", fontsize=9, color="#333333",
        bbox=dict(facecolor="#e3f2fd", edgecolor="#90caf9", boxstyle="round,pad=0.3"))

# 箭头 1→2
ax.annotate("", xy=(x_center + box_w/2, y0 - 1.2), xytext=(x_center + box_w/2, y0 - box_h/2),
            arrowprops=dict(arrowstyle="->", color="#999999", lw=1.5))

# ==================== 第2层：QueryRewrite ====================
y1 = 12.2
ax.add_patch(FancyBboxPatch((x_center, y1 - box_h/2), box_w, box_h,
    boxstyle="round,pad=0.08", facecolor=C_REWRITE, edgecolor="white", linewidth=1.2))
ax.text(x_center + box_w/2, y1, "QueryRewrite  查询改写", ha="center", va="center",
        fontsize=10, fontweight="bold", color="white")
ax.text(x_center + box_w/2, y1 - 0.70, '"使用 PyTorch 搭建 CNN 的关键步骤有哪些"  |  耗时 50ms',
        ha="center", va="top", fontsize=8, color="#666666",
        bbox=dict(facecolor="#f3e5f5", edgecolor="#ce93d8", boxstyle="round,pad=0.25"))

ax.annotate("", xy=(x_center + box_w/2, y1 - 1.2), xytext=(x_center + box_w/2, y1 - box_h/2),
            arrowprops=dict(arrowstyle="->", color="#999999", lw=1.5))

# ==================== 第3层：IntentRecognition ====================
y2 = 10.5
ax.add_patch(FancyBboxPatch((x_center, y2 - box_h/2), box_w, box_h,
    boxstyle="round,pad=0.08", facecolor=C_INTENT, edgecolor="white", linewidth=1.2))
ax.text(x_center + box_w/2, y2, "IntentRecognition  意图识别", ha="center", va="center",
        fontsize=10, fontweight="bold", color="white")
ax.text(x_center + box_w/2, y2 - 0.70, "意图: kb/pytorch, 置信度: 高  |  耗时 320ms",
        ha="center", va="top", fontsize=8, color="#666666",
        bbox=dict(facecolor="#f3e5f5", edgecolor="#ce93d8", boxstyle="round,pad=0.25"))

ax.annotate("", xy=(x_center + box_w/2, y2 - 1.2), xytext=(x_center + box_w/2, y2 - box_h/2),
            arrowprops=dict(arrowstyle="->", color="#999999", lw=1.5))

# ==================== 第4层：MultiChannelRetrieval 分支 ====================
y3 = 8.5
# 大框
branch_box_w, branch_box_h = 13.2, 3.8
bx, by = 0.4, y3 - 2.3
ax.add_patch(FancyBboxPatch((bx, by), branch_box_w, branch_box_h,
    boxstyle="round,pad=0.15", facecolor="#fafafa", edgecolor="#bdbdbd",
    linewidth=1.2, linestyle="--", zorder=0))

# 标题
ax.text(bx + branch_box_w/2, y3 + 0.15, "MultiChannelRetrieval  多路并行检索",
        ha="center", va="bottom", fontsize=11, fontweight="bold", color="#424242")

# 三路分支
sub_w, sub_h = 3.8, 1.3
sub_y = y3 - 0.85
gaps = [1.5, 7.1, 12.7]  # 改为绝对 x 坐标方便管理
sub_colors = [C_VECTOR, C_VECTOR, C_KW]
sub_labels = [
    "IntentDirectedSearch\n向量定向",
    "VectorGlobalSearch\n向量全局",
    "KeywordSearchChannel\n关键词",
]
sub_times = ["158ms → 5 chunk", "4,384ms → 12 chunk", "5ms → 12 chunk"]
sub_xs = [1.0, 5.2, 9.4]

# 从主节点分叉的连线
main_x = x_center + box_w/2
main_y = y3 + 0.6
for sx in sub_xs:
    cx = sx + sub_w/2
    ax.plot([main_x, main_x, cx, cx], [main_y, y3 + 0.3, y3 + 0.3, sub_y + sub_h/2],
            color="#bdbdbd", lw=1, zorder=0)

for i, (sx, label, time_str, color) in enumerate(zip(sub_xs, sub_labels, sub_times, sub_colors)):
    ax.add_patch(FancyBboxPatch((sx, sub_y - sub_h/2), sub_w, sub_h,
        boxstyle="round,pad=0.08", facecolor=color, edgecolor="white", linewidth=1.2))
    ax.text(sx + sub_w/2, sub_y + 0.18, label, ha="center", va="center",
            fontsize=8.5, fontweight="bold", color="white")
    ax.text(sx + sub_w/2, sub_y - 0.55, time_str, ha="center", va="center",
            fontsize=8, color="white", alpha=0.9)

# 并行耗时标注
merge_y = sub_y - sub_h/2 - 0.55
ax.text(x_center + box_w/2 - 3.5, merge_y + 0.05,
        "并行耗时: max(158, 4384, 5) = 4,384ms",
        ha="left", va="top", fontsize=8.5, color="#e65100", fontweight="bold",
        bbox=dict(facecolor="#fff3e0", edgecolor="#ffb74d", boxstyle="round,pad=0.3"))

# 三路汇聚箭头
merge_top = sub_y - sub_h/2
for sx in sub_xs:
    cx = sx + sub_w/2
    ax.plot([cx, cx, x_center + box_w/2 + 3.5, x_center + box_w/2 + 3.5],
            [merge_top, merge_y + 0.25, merge_y + 0.25, merge_y + 0.25],
            color="#bdbdbd", lw=1, zorder=0)

# 汇聚后的向下箭头
ax.annotate("", xy=(x_center + box_w/2, by - 0.1), xytext=(x_center + box_w/2, merge_y + 0.25),
            arrowprops=dict(arrowstyle="->", color="#999999", lw=1.5))

# ==================== 第5层：Deduplication ====================
y4 = 5.4
ax.add_patch(FancyBboxPatch((x_center, y4 - box_h/2), box_w, box_h,
    boxstyle="round,pad=0.08", facecolor=C_DEDUP, edgecolor="white", linewidth=1.2))
ax.text(x_center + box_w/2, y4, "DeduplicationPostProcessor  去重", ha="center", va="center",
        fontsize=10, fontweight="bold", color="white")
ax.text(x_center + box_w/2, y4 - 0.70, "17 chunk → 12 chunk (去重5个)  |  耗时 <1ms",
        ha="center", va="top", fontsize=8, color="#666666",
        bbox=dict(facecolor="#e8f5e9", edgecolor="#a5d6a7", boxstyle="round,pad=0.25"))

ax.annotate("", xy=(x_center + box_w/2, y4 - 1.2), xytext=(x_center + box_w/2, y4 - box_h/2),
            arrowprops=dict(arrowstyle="->", color="#999999", lw=1.5))

# ==================== 第6层：RRF Fusion ====================
y5 = 3.9
ax.add_patch(FancyBboxPatch((x_center, y5 - box_h/2), box_w, box_h,
    boxstyle="round,pad=0.08", facecolor=C_FUSION, edgecolor="white", linewidth=1.2))
ax.text(x_center + box_w/2, y5, "HybridFusionPostProcessor  RRF融合", ha="center", va="center",
        fontsize=10, fontweight="bold", color="white")
ax.text(x_center + box_w/2, y5 - 0.70, "向量12 + 关键词12 → 融合12  |  耗时 <1ms",
        ha="center", va="top", fontsize=8, color="#666666",
        bbox=dict(facecolor="#e8f5e9", edgecolor="#a5d6a7", boxstyle="round,pad=0.25"))

ax.annotate("", xy=(x_center + box_w/2, y5 - 1.2), xytext=(x_center + box_w/2, y5 - box_h/2),
            arrowprops=dict(arrowstyle="->", color="#999999", lw=1.5))

# ==================== 第7层：Rerank ====================
y6 = 2.4
ax.add_patch(FancyBboxPatch((x_center, y6 - box_h/2), box_w, box_h,
    boxstyle="round,pad=0.08", facecolor=C_RERANK, edgecolor="white", linewidth=1.2))
ax.text(x_center + box_w/2, y6, "RerankPostProcessor  重排序", ha="center", va="center",
        fontsize=10, fontweight="bold", color="white")
ax.text(x_center + box_w/2, y6 - 0.70, "12 chunk → Top-10  |  耗时 450ms",
        ha="center", va="top", fontsize=8, color="#666666",
        bbox=dict(facecolor="#e8f5e9", edgecolor="#a5d6a7", boxstyle="round,pad=0.25"))

ax.annotate("", xy=(x_center + box_w/2, y6 - 1.2), xytext=(x_center + box_w/2, y6 - box_h/2),
            arrowprops=dict(arrowstyle="->", color="#999999", lw=1.5))

# ==================== 第8层：ContextAssembly ====================
y7 = 1.1
ctx_w = 3.8
ctx_x = x_center + (box_w - ctx_w)/2
ax.add_patch(FancyBboxPatch((ctx_x, y7 - box_h/2), ctx_w, box_h,
    boxstyle="round,pad=0.08", facecolor=C_CTX, edgecolor="white", linewidth=1.2))
ax.text(ctx_x + ctx_w/2, y7, "ContextAssembly  上下文组装", ha="center", va="center",
        fontsize=9, fontweight="bold", color="white")
ax.text(ctx_x + ctx_w/2, y7 - 0.55, "耗时 15ms", ha="center", va="top",
        fontsize=7.5, color="#999999")

# LLM 和 SSE 并排
# LLM 在左边
llm_x = 1.4
llm_w = 4.8
llm_y = y7
ax.add_patch(FancyBboxPatch((llm_x, llm_y - box_h/2), llm_w, box_h,
    boxstyle="round,pad=0.08", facecolor=C_LLM, edgecolor="white", linewidth=1.5))
ax.text(llm_x + llm_w/2, llm_y + 0.1, "LLM  模型调用", ha="center", va="center",
        fontsize=10, fontweight="bold", color="white")
ax.text(llm_x + llm_w/2, llm_y - 0.55, "首包: 2,800ms, 总生成: 8,500ms",
        ha="center", va="top", fontsize=8, color="white", alpha=0.9)

# SSE 在右边
sse_x = 8.1
sse_w = 4.3
sse_y = y7
ax.add_patch(FancyBboxPatch((sse_x, sse_y - box_h/2), sse_w, box_h,
    boxstyle="round,pad=0.08", facecolor=C_SSE, edgecolor="white", linewidth=1.5))
ax.text(sse_x + sse_w/2, sse_y, "SSE  流式输出 → 客户端", ha="center", va="center",
        fontsize=10, fontweight="bold", color="white")

# ContextAssembly → LLM 和 SSE 的连线
arrow_y = y7 + box_h/2 + 0.05
ctx_cx = ctx_x + ctx_w/2
ax.annotate("", xy=(llm_x + llm_w/2, y7 + box_h/2), xytext=(ctx_cx, arrow_y),
            arrowprops=dict(arrowstyle="->", color="#999999", lw=1.2))
ax.annotate("", xy=(sse_x + sse_w/2, y7 + box_h/2), xytext=(ctx_cx, arrow_y),
            arrowprops=dict(arrowstyle="->", color="#999999", lw=1.2))

# ==================== 图例 ====================
legend_items = [
    ("查询输入", C_QUERY),
    ("预处理 (改写+意图)", C_REWRITE),
    ("向量检索", C_VECTOR),
    ("关键词检索", C_KW),
    ("后处理 (去重/融合/重排)", C_DEDUP),
    ("上下文+LLM+SSE", C_LLM),
]
legend_patches = [mpatches.Patch(color=c, label=t) for t, c in legend_items]
ax.legend(handles=legend_patches, loc="lower center", ncol=6, fontsize=7.5,
          framealpha=0.9, bbox_to_anchor=(0.5, -0.06))

# 总耗时标注
ax.text(13.5, 14.5, "端到端首包延迟\n≈ 7,635ms", ha="center", va="top",
        fontsize=9, fontweight="bold", color="#c62828",
        bbox=dict(facecolor="#ffebee", edgecolor="#ef9a9a", boxstyle="round,pad=0.5"))

ax.set_title("RagentAI 混合检索链路 Trace — 测试用例1", fontsize=14, fontweight="bold", pad=8)

plt.tight_layout()
plt.savefig("docs_my/rag_trace.png", dpi=200, bbox_inches="tight")
print("Saved to docs_my/rag_trace.png")
