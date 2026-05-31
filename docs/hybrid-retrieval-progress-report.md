# 混合检索通道上线进展报告

## 一、概述

本次在已有的向量检索引擎基础上，新增了第三路 **关键词全文检索通道** 和 **混合融合后置处理器**，形成 "向量检索 + 关键词检索 → 去重 → 融合 → Rerank" 的完整流水线。旨在弥补向量检索对专有名词、产品型号等精确关键词的召回不足。

---

## 二、架构变更

原有的多通道检索架构只有两条向量通道（`VECTOR_GLOBAL` 全局向量检索 + `INTENT_DIRECTED` 意图定向检索），现在扩展为三通道并行，并在后置处理器链中插入融合环节。完整流水线顺序为：

```
Question
  → [VectorGlobalChannel | IntentDirectedChannel | KeywordSearchChannel]  // Stage 1: 并行检索
  → DeduplicationPostProcessor                                           // Stage 2: 去重 (order=1)
  → HybridFusionPostProcessor                                            // Stage 3: 融合 (order=5)
  → RerankPostProcessor                                                  // Stage 4: 精排 (order=10)
  → Final TopK
```

核心编排逻辑在 `MultiChannelRetrievalEngine.retrieveKnowledgeChannels()` (bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java:65)，分三个阶段：构建上下文 → 并行执行所有启用的通道 → 链式执行后置处理器。

---

## 三、变更文件清单

### 后端新增/修改 (Java)

| 文件 | 行数 | 说明 |
|---|---|---|
| `channel/KeywordSearchChannel.java` | 193 | 关键词检索通道，使用 PG tsvector/tsquery 全文检索 |
| `channel/fusion/FusionStrategy.java` | 39 | 多通道结果融合策略接口 |
| `channel/fusion/RRFFusionStrategy.java` | 75 | RRF (Reciprocal Rank Fusion) 融合实现 |
| `channel/fusion/WeightedSumFusionStrategy.java` | 79 | 加权求和融合实现（含 min-max 归一化） |
| `postprocessor/HybridFusionPostProcessor.java` | 135 | 混合融合后置处理器，编排向量+关键词结果合并 |
| `config/SearchChannelProperties.java` | 155 | 通道配置属性类（keyword、hybrid 子配置） |
| `controller/RAGSettingsController.java` | +33 | 新增 `toChannelSettings()` 暴露通道配置到 `/rag/settings` |
| `controller/vo/SystemSettingsVO.java` | +69 | 新增 ChannelSettings、HybridChannelConfig 等 VO |

### 前端修改 (TypeScript)

| 文件 | 说明 |
|---|---|
| `pages/admin/settings/SystemSettingsPage.tsx` | 新增关键词通道和混合融合的设置展示区块 |
| `services/settingsService.ts` | 补充 `keyword` / `hybrid` 接口类型定义 |

### 数据库变更

| 文件 | 说明 |
|---|---|
| `resources/database/schema_pg.sql` | 新增 `tsv tsvector` 列 + GIN 索引定义 |
| `resources/database/upgrade_v1.2_to_v1.3.sql` | 升级脚本（列、索引、触发器、存量数据回填） |

---

## 四、各模块详细说明

### 4.1 KeywordSearchChannel — 关键词全文检索通道

**文件**: `bootstrap/.../rag/core/retrieve/channel/KeywordSearchChannel.java`

- **激活条件**: 仅在 `rag.vector.type=pg` 时生效（`@ConditionalOnProperty`），依赖 PostgreSQL 原生的 `tsvector`/`tsquery` 能力。
- **检索流程**:
  1. 查询所有知识库的 collection 名称
  2. 对每个 collection 并行执行 SQL 全文检索（`CompletableFuture` + `innerRetrievalExecutor`）
  3. 使用 `plainto_tsquery('simple', ?)` 做分词匹配，`ts_rank` 计算相关性分数
  4. 并行收集各 collection 结果后汇总统计（成功/失败数、chunk 总数）
- **安全处理**: 对用户输入做字符清洗 `[^\w一-鿿\s]`，防止 `tsquery` 解析错误。
- **TopK**: `context.getTopK() × keyword.topKMultiplier`（默认 3x），保证融合阶段有足够候选。
- **优先级**: `getPriority() = 20`，在向量通道之后执行。

### 4.2 HybridFusionPostProcessor — 混合融合后置处理器

**文件**: `bootstrap/.../rag/core/retrieve/postprocessor/HybridFusionPostProcessor.java`

- **执行顺序**: `getOrder() = 5`，位于去重 (order=1) 之后、Rerank (order=10) 之前。
- **融合逻辑**:
  - 按通道类型分组：`keyword`（来自 KEYWORD_ES）和 `vector`（来自 VECTOR_GLOBAL / INTENT_DIRECTED）
  - 若任一路缺失，跳过融合直接返回原结果
  - 根据配置选择融合策略（RRF 或 WeightedSum）
  - 权重：`vectorWeight`（默认 0.7），keyword 权重自动为 `1 - vectorWeight`
- **日志**: 融合时输出 `向量 N + 关键词 M → 融合后 X` 便于观测。

### 4.3 融合策略

| 策略 | 类 | 核心公式 | 特点 |
|---|---|---|---|
| **RRF** (默认) | `RRFFusionStrategy` | `score = Σ 1/(k + rank_i)` 其中 k=60 | 不依赖原始分数归一化，对各通道量纲差异不敏感，仅依赖排名信息 |
| **加权求和** | `WeightedSumFusionStrategy` | `score = Σ w_i × norm(score_i)` | 先对各通道分数做 min-max 归一化，再按权重加权求和，可精细调节各通道影响 |

去重处理 (`DeduplicationPostProcessor`) 的通道优先级也做了相应调整：`INTENT_DIRECTED(1) > KEYWORD_ES(2) > VECTOR_GLOBAL(3)`，去重冲突时意图定向检索结果优先保留。

### 4.4 配置体系

**文件**: `bootstrap/.../rag/config/SearchChannelProperties.java`

完整配置前缀为 `rag.search.channels.*`，所有默认值内建在类中：

```yaml
rag:
  search:
    channels:
      keyword:
        enabled: true          # 是否启用关键词检索通道
        topKMultiplier: 3      # 召回倍数
        boost: 1.0             # 关键词通道权重(WEIGHTED_SUM 模式)
      hybrid:
        enabled: true          # 是否启用混合融合
        fusion: RRF            # 融合策略: RRF | WEIGHTED_SUM
        vectorWeight: 0.7      # 向量通道权重(1 - vectorWeight 为关键词权重)
```

配置通过 `RAGSettingsController.getSettings()` 暴露到前端 `/rag/settings` API。

### 4.5 数据库变更

`t_knowledge_vector` 表新增：

- **`tsv tsvector` 列**: 存储 `to_tsvector('simple', content)` 的预计算词向量
- **GIN 索引** `idx_kv_tsv`: 加速 `tsv @@ tsquery` 全文检索
- **触发器** `trg_kv_tsv`: `BEFORE INSERT OR UPDATE OF content` 时自动维护 `tsv`，避免应用层数据不一致
- **存量回填**: `UPDATE t_knowledge_vector SET tsv = to_tsvector('simple', COALESCE(content, ''))`

### 4.6 前端变更

系统设置页 (`SystemSettingsPage.tsx`) 新增两个配置展示区块：

- **关键词检索**: 显示 `enabled`、`topKMultiplier`、`boost`
- **混合融合**: 显示 `enabled`、`fusion` 策略名、`vectorWeight`

---

## 五、设计亮点

1. **插件化通道架构**: `SearchChannel` 接口 + `SearchResultPostProcessor` 链式处理。新增通道只需实现接口即可自动注册到 `MultiChannelRetrievalEngine`，无需修改引擎代码。
2. **策略模式融合**: `FusionStrategy` 接口抽象，RRF 和 WeightedSum 可随时切换，后续新增其他融合算法只需增加一个实现类。
3. **并行执行**: 所有通道通过 `CompletableFuture` 并行调用，关键词检索不增加串行延迟。
4. **配置驱动**: 所有参数通过 Spring Boot `@ConfigurationProperties` 绑定，前端 API 透传，支持运维动态调整（需重启）。
5. **自动索引维护**: 通过 PostgreSQL 触发器而非应用层代码维护 `tsv` 列，保证数据一致性。

---

## 六、当前状态

**已完成**:
- [x] KeywordSearchChannel 实现（PG 全文检索，支持多 collection 并行检索）
- [x] RRF 与 WeightedSum 两种融合策略
- [x] HybridFusionPostProcessor 编排到后置处理器链（去重之后、Rerank 之前）
- [x] 配置属性类与 YAML 默认值
- [x] 系统设置 API 暴露通道配置（`/rag/settings`）
- [x] 前端设置页展示关键词和融合配置
- [x] 数据库升级脚本（v1.2 → v1.3）及 schema 同步
- [x] 存量数据 tsv 列回填

**待跟进**:
- [ ] RAG 对话接口中关键词检索的实际召回效果评估（专有名词/型号类查询的 hit rate 对比）
- [ ] `KEYWORD_ES` 通道类型重命名评估（当前命名暗示 Elasticsearch，实际使用 PostgreSQL）
- [ ] 中文分词词典支持（目前 hardcode `'simple'`，不支持 jieba / zhparser 等中文分词）
- [ ] 混合融合权重（`vectorWeight`）根据实际业务场景调优
