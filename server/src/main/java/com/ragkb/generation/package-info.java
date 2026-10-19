/**
 * 生成:提示词拼装、答案生成、引用校验、证据评估与拒答。
 *
 * TODO(生成): 本包尚无任何类。计划放入:
 * <ul>
 *   <li>PromptBuilder —— 把候选切片拼成带编号的上下文</li>
 *   <li>AnswerGenerator —— 调模型生成答案</li>
 *   <li>CitationParser —— 解析答案里的编号并回查切片</li>
 *   <li>EvidenceEvaluator —— 判定证据是否足以回答,不足则拒答</li>
 * </ul>
 * 两条不能省的原则:引用编号由程序生成、不能信任模型自己编的编号(出现不存在的编号要剔除);
 * 拒答阈值不能拍脑袋,要从评估集反推。
 */
package com.ragkb.generation;
