package com.ragkb.chunk;

import com.ragkb.parse.ParsedDocument;

import java.util.List;

/**
 * 切片器:把结构块序列切成可入库、可检索的片段。
 *
 * 为什么切片值得单独设计:切片策略决定检索上限。切坏了,后面 RRF、rerank、
 * 提示词工程全都救不回来 —— 因为需要的那句话压根不在任何一个切片里。
 * 这一步没有可以照搬的通用做法,切法必须由语料的真实结构决定。
 *
 * 实现时必须满足的硬约束(违反了验收过不了,而且往往是静默出错):
 * <ol>
 *   <li><b>下标可回溯</b>:对每个切片,{@code document.slice(charStart, charEnd)}
 *       必须能取回这段正文(允许首尾空白差异,不允许内容不同)。</li>
 *   <li><b>顺序且不重叠地覆盖</b>:index 从 0 连续递增;除 overlap 区间外,
 *       相邻切片不得大段重复;把所有切片按下标拼起来应能还原全文。</li>
 *   <li><b>token 上限</b>:tokenCount 必须来自 {@link TokenCounter},
 *       且不得超过配置的单片上限。</li>
 *   <li><b>不产出空白片</b>:切完只剩标点或空白的片段要丢掉,不要落库。</li>
 * </ol>
 *
 * 三个参数需要按实测数据定,不要套用默认值:
 * target(目标大小) / max(硬上限) / overlap(重叠)。
 * 定之前先用 {@code scripts/preview-structure.sh} 看语料的真实结构分布,
 * 再决定:标题平均多长、一个自然段平均多少 token、最长的表格行多少 token。
 */
public interface ChunkSplitter {

    /**
     * 把解析结果切成可入库的切片。
     *
     * @param document 解析器产出,含全文与结构块
     * @return 顺序切片的草稿列表;文档为空时返回空列表,不得返回 null
     */
    List<ChunkDraft> split(ParsedDocument document);
}
