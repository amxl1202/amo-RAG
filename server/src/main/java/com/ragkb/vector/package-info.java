/**
 * 向量存储:pgvector 的写入、索引与查询。
 *
 * 检索只通过本包拿数据,不直接写 SQL —— 换索引类型(HNSW / IVFFlat)时不用动检索代码。
 *
 * TODO(向量化): 本包尚无任何类。计划放入:
 * <ul>
 *   <li>ChunkEmbeddingEntity / ChunkEmbeddingMapper —— 向量表(与切片表分开,理由见 DocumentChunkEntity)</li>
 *   <li>VectorStoreRepository —— 批量写入、按余弦距离取 Top-K</li>
 * </ul>
 * 建表时记得同时建 HNSW 索引,否则数据量上来后查询会退化成全表扫描。
 */
package com.ragkb.vector;
