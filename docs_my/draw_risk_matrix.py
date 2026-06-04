import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

# ===================== 新增：全局中文字体配置 =====================
plt.rcParams["font.family"] = ["SimHei", "WenQuanYi Micro Hei", "Heiti TC", "sans-serif"]
plt.rcParams["axes.unicode_minus"] = False  # 解决负号显示为方块的问题
# ================================================================

# 风险数据: (标签, 可能性x, 影响y, 颜色偏移)
# 可能性: 低=0, 中=1, 高=2
# 影响:   低=0, 中=1, 高=2
risks = [
    # 高影响区域
    ("迁移锁表", 0, 2),
    ("PG升级兼容", 0, 2),
    ("zhparser失败", 1, 2),
    ("模型不可用", 1, 2),
    ("emb波动", 2, 2),
    ("token遗漏", 2, 2),
    # 中影响区域
    ("端口冲突", 1, 1),
    ("前端不一致", 1, 1),
    ("知识库增长", 1, 1),
    ("镜像构建", 1, 1),
    ("依赖升级", 1, 1),
    # 低影响区域
    ("OR过多", 0, 0),
    ("PG负载", 0, 0),
]

# 手工微调每个标签的偏移，避免重叠
offsets = {
    "迁移锁表":    (-0.18, -0.10),
    "PG升级兼容":  (0.18, -0.10),
    "zhparser失败": (-0.18, -0.38),
    "模型不可用":  (0.18, -0.38),
    "emb波动":     (-0.18, -0.10),
    "token遗漏":   (0.18, -0.38),
    "端口冲突":    (-0.28,  0.15),
    "前端不一致":  (0.00,  0.15),
    "知识库增长":  (0.28,  0.15),
    "镜像构建":    (-0.18, -0.18),
    "依赖升级":    (0.18, -0.18),
    "OR过多":      (-0.12,  0.15),
    "PG负载":      (0.12,  0.15),
}

fig, ax = plt.subplots(figsize=(10, 8))

# 背景色块（风险等级）
colors = {
    (0, 0): "#c8e6c9",  # 低-低: 绿
    (1, 0): "#fff9c4",  # 中-低: 黄绿
    (2, 0): "#ffcc02",  # 高-低: 黄
    (0, 1): "#fff9c4",  # 低-中: 黄绿
    (1, 1): "#ffcc02",  # 中-中: 黄
    (2, 1): "#ff9100",  # 高-中: 橙
    (0, 2): "#ffcc02",  # 低-高: 黄
    (1, 2): "#ff9100",  # 中-高: 橙
    (2, 2): "#ff5252",  # 高-高: 红
}

# 绘制3x3网格背景
for x in range(3):
    for y in range(3):
        rect = mpatches.FancyBboxPatch(
            (x - 0.5, y - 0.5), 1, 1,
            boxstyle="round,pad=0.02",
            facecolor=colors[(x, y)],
            edgecolor="#cccccc",
            linewidth=0.5,
            alpha=0.7,
        )
        ax.add_patch(rect)

# 绘制风险点
for label, x, y in risks:
    ox, oy = offsets.get(label, (0, 0))
    px, py = x + ox, y + oy
    ax.plot(px, py, "o", color="#1565c0", markersize=10, zorder=5,
            markeredgecolor="white", markeredgewidth=1.2)
    # 标签背景框
    ax.annotate(
        label,
        (px, py),
        textcoords="offset points",
        xytext=(0, 12),
        ha="center",
        va="bottom",
        fontsize=9,
        # 移除 fontfamily，使用全局字体配置
        bbox=dict(boxstyle="round,pad=0.3", facecolor="white",
                  edgecolor="#bbbbbb", alpha=0.85),
        zorder=6,
    )

# 坐标轴
ax.set_xlim(-0.7, 2.7)
ax.set_ylim(-0.7, 2.7)
ax.set_xticks([0, 1, 2])
ax.set_xticklabels(["低", "中", "高"], fontsize=12)
ax.set_yticks([0, 1, 2])
ax.set_yticklabels(["低", "中", "高"], fontsize=12)

ax.set_xlabel("可能性 →", fontsize=13, labelpad=10)
ax.set_ylabel("影响", fontsize=13, labelpad=10, rotation=0)
ax.yaxis.set_label_coords(-0.12, 0.92)

# 图例
legend_patches = [
    mpatches.Patch(color="#ff5252", alpha=0.7, label="高风险 (红色)"),
    mpatches.Patch(color="#ff9100", alpha=0.7, label="中高风险 (橙色)"),
    mpatches.Patch(color="#ffcc02", alpha=0.7, label="中风险 (黄色)"),
    mpatches.Patch(color="#fff9c4", alpha=0.7, label="低中风险 (黄绿)"),
    mpatches.Patch(color="#c8e6c9", alpha=0.7, label="低风险 (绿色)"),
]
ax.legend(handles=legend_patches, loc="upper left", fontsize=9,
          bbox_to_anchor=(1.02, 1), borderaxespad=0, framealpha=0.9)

# 网格线
for i in range(-1, 4):
    ax.axhline(i - 0.5, color="#999999", linewidth=0.6, zorder=1)
    ax.axvline(i - 0.5, color="#999999", linewidth=0.6, zorder=1)

ax.set_title("风险矩阵总览", fontsize=15, fontweight="bold", pad=15)
ax.set_aspect("equal")
ax.tick_params(top=False, right=False, length=0)

plt.tight_layout()
plt.savefig("risk_matrix.png", dpi=200, bbox_inches="tight")
plt.show()
print("Saved to risk_matrix.png")