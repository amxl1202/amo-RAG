package com.ragkb.retrieval;

import com.ragkb.vector.ScoredChunk;

import java.util.List;

/**
 * 单个检索通道的统一形状:问题 + 条数 → 命中片段。
 *
 * 抽出它有两个用处:
 * <ol>
 *   <li>{@link HybridRetriever} 不依赖具体实现,并行调用的两路可以用同一段代码跑;</li>
 *   <li>测试里可以直接用 lambda 构造通道 —— 超时、异常、慢响应这些场景
 *       不需要真去构造一个向量检索器。</li>
 * </ol>
 */
@FunctionalInterface
public interface ChunkSearcher {

    /**
     * @param tenantId 租户。这个参数是必填而不是可选,是为了让"忘了过滤"变成<b>编译错误</b>:
     *                 跨租户漏检的代价是数据泄漏,靠自觉或 code review 兜不住
     */
    List<ScoredChunk> search(String question, int limit, long tenantId);
}
