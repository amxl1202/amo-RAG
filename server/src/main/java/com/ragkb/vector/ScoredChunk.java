package com.ragkb.vector;

/**
 * 一次向量检索命中的切片。
 *
 * 除了相似度,还带上切片自身的定位信息(章节路径与字符区间)—— 检索层不负责拼答案,
 * 但它得把"引用要指向哪里"一并交出去,否则下游还得回查一次。
 *
 * @param score 余弦相似度(1 - 余弦距离),越大越相关;范围约 [-1, 1],实际文本向量多落在 0~1
 */
public record ScoredChunk(
        long chunkId,
        long documentId,
        String content,
        String sectionPath,
        int charStart,
        int charEnd,
        Integer pageStart,
        Integer pageEnd,
        double score) {
}
