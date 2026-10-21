package com.ragkb.qa;

import com.ragkb.generation.Citation;
import com.ragkb.generation.Evidence;

import java.util.List;

/**
 * 问答响应。
 *
 * <h2>为什么拒答也是 200</h2>
 * 拒答是<b>业务结果</b>,不是协议错误:"文档里没有这个信息"与"你请求的方式不对"是两回事。
 * 用 4xx/5xx 表达拒答,前端就只能靠状态码分支去猜,而真正的错误反而淹在里面。
 * 状态放在 body 的 {@code status} 字段里,前端据此渲染不同样式。
 *
 * <h2>为什么引用要带正文</h2>
 * 前端的"点击引用定位原文"要立刻展示那一段。只给 {@code chunkId} 就得再开一个接口回查,
 * 多一次往返、多一个能失败的环节 —— 而这段正文本来就在服务端手里。
 * 字符区间一起带上,是为了将来做"在原文里高亮"时不必再改契约。
 *
 * @param question       问题原文(回显,便于前端做乐观 UI 的对账)
 * @param status         ANSWERED / REFUSED / FAILED
 * @param answer         答案文本;拒答时是固定串
 * @param citations      答案引用到的切片
 * @param reason         拒答或失败的原因码;正常作答时为 null
 * @param promptTokens   模型输入 token
 * @param completionTokens 模型输出 token
 * @param retrievalMillis 检索耗时
 * @param answerMillis    生成 + 自检耗时
 */
public record QaResponse(
        String question,
        String status,
        String answer,
        List<CitationView> citations,
        String reason,
        int promptTokens,
        int completionTokens,
        long retrievalMillis,
        long answerMillis) {

    /** 引用视图。字段与前端抽屉一一对应,不做二次裁剪。 */
    public record CitationView(
            String label,
            long chunkId,
            long documentId,
            String documentTitle,
            String sectionPath,
            Integer pageStart,
            Integer pageEnd,
            int charStart,
            int charEnd,
            String content) {

        static CitationView from(Citation citation) {
            Evidence evidence = citation.evidence();
            return new CitationView(citation.label(), citation.chunkId(), citation.documentId(),
                    evidence.documentTitle(), evidence.sectionPath(),
                    evidence.pageStart(), evidence.pageEnd(),
                    evidence.charStart(), evidence.charEnd(), evidence.content());
        }
    }

    public static QaResponse from(QaAnswer answer) {
        return new QaResponse(
                answer.question(),
                answer.status().name(),
                answer.text(),
                answer.citations().stream().map(CitationView::from).toList(),
                answer.reason() == null ? null : answer.reason().name(),
                answer.promptTokens(),
                answer.completionTokens(),
                answer.retrievalMillis(),
                answer.answerMillis());
    }
}
