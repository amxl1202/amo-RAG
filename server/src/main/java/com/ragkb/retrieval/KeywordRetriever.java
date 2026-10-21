package com.ragkb.retrieval;

/**
 * 关键词检索通道。
 *
 * 与向量通道并列,靠"精确字串"补向量模型的短板:产品型号、错误码、专有名词这类
 * 向量模型没有充分学习过的字符串,语义检索经常召不回,而字串匹配几乎不会漏。
 */
public interface KeywordRetriever extends ChunkSearcher {
}
