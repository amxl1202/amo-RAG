/**
 * 检索:向量通道、关键词通道、融合排序。
 *
 * 只依赖切片产出与向量查询能力,不认识 HTTP、不认识上传 —— 这样它能在命令行里单独调试。
 *
 * TODO(检索): 本包尚无任何类。计划放入:
 * <ul>
 *   <li>VectorRetriever —— 语义检索</li>
 *   <li>KeywordRetriever —— 关键词检索。<b>中文要先解决分词</b>:
 *       PG 默认的 tsvector 不切中文,直接用会得不到词项;
 *       候选方案是 ILIKE 子串、pg_trgm 三元组、pg_jieba 分词,选哪个要用召回率对比决定</li>
 *   <li>HybridRetriever —— 两通道<b>并行</b>调用(不要写成串行 for,会白等两倍时间)</li>
 *   <li>RrfFuser —— 按排名融合。两通道的分数不可直接相加(量纲不同),所以只用名次</li>
 * </ul>
 */
package com.ragkb.retrieval;
