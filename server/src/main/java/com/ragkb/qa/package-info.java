/**
 * 问答:把"问题 → 检索 → 生成 → 引用"串成一次请求。
 *
 * 这一层只做编排,不实现检索与生成的细节 —— 那些在 retrieval / generation 里。
 *
 * TODO(问答): 本包尚无任何类。计划放入:
 * <ul>
 *   <li>QaController —— 提问接口</li>
 *   <li>QaService —— 编排:检索 → 证据评估 → 生成 → 引用回填</li>
 *   <li>QaRequest / QaResponse —— 请求与响应模型(响应要带引用列表)</li>
 *   <li>QaRecordEntity / Mapper —— 问答记录,便于事后看哪些问题答得差</li>
 * </ul>
 */
package com.ragkb.qa;
