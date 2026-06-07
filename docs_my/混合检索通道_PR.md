# feat(retrieve): 新增混合检索通道，支持PG全文检索+zhparser中文分词+RRF融合

---

# Issue

## 背景

当前 RAG 系统仅支持向量检索（VectorGlobalSearchChannel + IntentDirectedSearchChannel）。向量检索对专有名词、产品型号等精确关键词（如"XPS-9380"、"Redis"）存在召回不足的问题。

## 方案概述

新增第三路检索通道 **KeywordSearchChannel**，利用 PostgreSQL tsvector/tsquery 实现关键词精确匹配，并通过 HybridFusionPostProcessor 将向量与关键词两路结果做 RRF（Reciprocal Rank Fusion）融合排序。

中文分词使用 zhparser（SCWS）PG 扩展，相比 `simple` 配置的按字切分，zhparser 按词语切分，大幅提升中文关键词检索精度。

## 改动范围

- **新增 6 个文件**：KeywordSearchChannel、HybridFusionPostProcessor、FusionStrategy 接口 + RRF/WeightedSum 实现
- **修改 6 个文件**：SearchChannelProperties 增加关键词/融合配置、RAGSettingsController + SystemSettingsVO 暴露通道配置到前端、前端设置页展示通道状态、schema_pg.sql + upgrade_v1.2_to_v1.3.sql 数据库迁移
- 共 12 files，827 行新增

## 配置项

```yaml
rag:
  search:
    channels:
      keyword:
        enabled: true
        topk-multiplier: 3
        boost: 1.0
      hybrid:
        enabled: true
        fusion: RRF           # RRF | WEIGHTED_SUM
        vector-weight: 0.7
```

---

# PR

## 概述

新增两阶段混合检索：Stage 1 并行执行向量检索 + 关键词检索，Stage 2 后处理器链融合排序。

```
用户提问
  → MultiChannelRetrievalEngine
    → 并行:
        IntentDirectedSearchChannel  (向量定向)
        VectorGlobalSearchChannel    (向量全局)
        KeywordSearchChannel          (PG全文检索) ← 新增
    → 后处理器:
        DeduplicationPostProcessor   (去重)
        HybridFusionPostProcessor    (RRF/加权融合) ← 新增
        RerankPostProcessor          (精排)
    → Top-K
```

## 文件清单

### 新增

| 文件 | 说明 |
|------|------|
| `channel/KeywordSearchChannel.java` | PG tsquery 关键词通道，zhparser 中文分词 |
| `postprocessor/HybridFusionPostProcessor.java` | RRF/加权求和融合后置处理器 |
| `fusion/FusionStrategy.java` | 融合策略接口 |
| `fusion/RRFFusionStrategy.java` | RRF 实现（默认） |
| `fusion/WeightedSumFusionStrategy.java` | 加权求和行为备选 |
| `resources/database/upgrade_v1.2_to_v1.3.sql` | 数据库迁移脚本 |

### 修改

| 文件 | 说明 |
|------|------|
| `SearchChannelProperties.java` | 增加 Keyword/Hybrid 配置块 + FusionMode 枚举 |
| `SystemSettingsVO.java` | 增加 ChannelSettings VO |
| `RAGSettingsController.java` | /rag/settings 返回通道配置 |
| `SystemSettingsPage.tsx` | 设置页新增检索通道配置卡片 |
| `settingsService.ts` | 前端类型定义 |
| `schema_pg.sql` | 全量建表脚本同步：tsv 列、zhparser 扩展、mapping、trigger |

## 配置项

```yaml
rag:
  search:
    channels:
      keyword:
        enabled: true
        topk-multiplier: 3
        boost: 1.0
      hybrid:
        enabled: true
        fusion: RRF           # RRF | WEIGHTED_SUM
        vector-weight: 0.7
```

## 数据库迁移

```bash
psql -U postgres -d ragent < resources/database/upgrade_v1.2_to_v1.3.sql
```

迁移脚本做以下操作：
1. `t_knowledge_vector` 新增 `tsv tsvector` 列 + GIN 索引
2. 创建 `zhparser` 扩展 + `CREATE TEXT SEARCH CONFIGURATION`
3. **关键**：`ADD MAPPING FOR n,v,a,i,e,l WITH simple` —— zhparser 产出的 token 类型必须映射到 simple 词典，否则 PG 丢弃所有 token，`to_tsvector` 返回空
4. 创建 trigger 自动维护 tsv 列
5. 回填已有数据

## 部署注意事项：Docker 镜像需编译 zhparser

当前项目使用 `docker run` 启动 `pgvector/pgvector:pg16`。关键词通道依赖 zhparser 扩展，该扩展需编译进 PG 镜像，无法通过挂载注入。

### 方案一：构建自定义镜像（推荐）

在项目根目录新建 `Dockerfile.pg`：

```dockerfile
FROM pgvector/pgvector:pg16
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential git wget postgresql-server-dev-16 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN wget -q http://www.xunsearch.com/scws/down/scws-1.2.3.tar.bz2 \
    && tar xf scws-1.2.3.tar.bz2 && cd scws-1.2.3 \
    && ./configure && make && make install && cd .. && rm -rf scws-1.2.3*
RUN wget -q https://github.com/amutu/zhparser/raw/master/dict.utf8.xdb \
    -O /usr/local/share/dict.utf8.xdb
RUN git clone https://github.com/amutu/zhparser.git \
    && cd zhparser && make && make install && cd .. && rm -rf zhparser
RUN ldconfig
```

构建并使用自定义镜像启动：

```bash
docker build -t pgvector-zhparser:pg16 -f Dockerfile.pg .
docker run -d \
  --name postgres \
  -e POSTGRES_DB=ragent \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  -v pgdata:/var/lib/postgresql/data \
  pgvector-zhparser:pg16
```

非 Docker 环境参考上述步骤在宿主机编译安装 SCWS + zhparser。

### 方案二：不启用关键词通道

如果不想引入编译步骤，KeywordSearchChannel 通过 `@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")` 条件装配。zhparser 扩展缺失时通道会自动跳过，不影响现有向量检索功能。也可手动关闭：

```yaml
rag.search.channels.keyword.enabled: false
rag.search.channels.hybrid.enabled: false
```
