/**
 * 向量化:把文本变成向量。
 *
 * 只依赖外部 embedding 接口,不依赖本项目其它包 —— 这样换代模型不会波及检索逻辑。
 *
 * TODO(向量化): 本包尚无任何类。计划放入:
 * <ul>
 *   <li>EmbeddingClient —— 单条与批量调用、失败重试、返回维度校验</li>
 *   <li>EmbeddingProperties —— 模型名、维度、批大小</li>
 * </ul>
 * 接入后要顺手做一件事:用接口返回的 usage.total_tokens 校准 TokenCounter 的偏差。
 */
package com.ragkb.embedding;
