package com.ragkb.generation;

import com.ragkb.vector.ScoredChunk;

/**
 * 一条待引用的证据:检索命中的切片,补齐了"人看得懂它出自哪里"所需的字段。
 *
 * <h2>为什么不直接传 {@link ScoredChunk}</h2>
 * 检索层的 {@code ScoredChunk} 只有 {@code documentId},而答案里的引用必须写清
 * "哪份文档、哪一章、第几页" —— 只给一个数字 ID,读者点开也不知道去哪找。
 * 文档标题属于文档元数据,检索层不该为了生成层去连一次文档表,所以这一层做转换。
 *
 * <h2>为什么带上字符区间</h2>
 * 前端的"点击引用定位原文"要靠 {@code charStart/charEnd} 在原文里摘出对应段落。
 * 这个区间是切片时就记下来的,生成层只做搬运,不重新计算 —— 一旦在这里重算,
 * 两套口径迟早会对不上,而对不上时表现为"高亮位置偏了几百字",极难定位。
 *
 * @param chunkId       切片 ID,回查原文与去重的键
 * @param documentId    所属文档 ID
 * @param documentTitle 文档标题(原始文件名);缺失时由调用方填占位,不要留 null
 * @param sectionPath   章节路径,如「第三章 &gt; 3.2 融合」
 * @param charStart     切片在文档纯文本中的起始下标(含)
 * @param charEnd       切片在文档纯文本中的结束下标(不含)
 * @param pageStart     起始页码,可为 null(纯文本没有页的概念)
 * @param pageEnd       结束页码,可为 null
 * @param content       切片正文
 * @param score         检索得分。语义随通道而变(余弦相似度 / 关键词得分 / RRF 融合分),
 *                      只用于排序与排查,<b>不要拿它跟固定阈值比</b>
 */
public record Evidence(
        long chunkId,
        long documentId,
        String documentTitle,
        String sectionPath,
        int charStart,
        int charEnd,
        Integer pageStart,
        Integer pageEnd,
        String content,
        double score) {

    /** 标题缺失时的占位。留 null 会让提示词里出现 "null" 字样,模型会当成内容的一部分。 */
    public static final String UNKNOWN_TITLE = "未命名文档";

    public Evidence {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("证据正文不能为空: chunkId=" + chunkId);
        }
        documentTitle = documentTitle == null || documentTitle.isBlank() ? UNKNOWN_TITLE : documentTitle;
    }

    /** 从检索结果构造。标题由调用方提供 —— 生成层不查库。 */
    public static Evidence from(ScoredChunk chunk, String documentTitle) {
        return new Evidence(chunk.chunkId(), chunk.documentId(), documentTitle, chunk.sectionPath(),
                chunk.charStart(), chunk.charEnd(), chunk.pageStart(), chunk.pageEnd(),
                chunk.content(), chunk.score());
    }
}
