package com.ragkb.eval;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 一次跑批的结果。
 *
 * 结构上刻意分两层、<b>永不合成总分</b>:
 * <ul>
 *   <li>{@link Retrieval} —— 检索层:找没找对位置</li>
 *   <li>{@link Generation} —— 生成层:基于找到的证据答得好不好、该不该拒答</li>
 * </ul>
 * 合成一个总分是评估里最常见的错误:分数掉了你不知道是检索坏了还是提示词坏了,
 * 于是只能整体重跑、凭感觉改。
 *
 * 生成层的四个指标目前恒为 {@code null}:生成流程还没接。
 * 用 null 而不是 0,是因为 0 会被读成"测了但全错",而后者的含义完全不同。
 */
public record EvalReport(
        String runAt,
        String retrieverName,
        int caseCount,
        int evaluableCases,
        Retrieval retrieval,
        Generation generation,
        String generationNote,
        List<FailedCase> failedCases) {

    /** 检索层指标。分母都是"参与评估的正向用例数",不含文档外负例。 */
    public record Retrieval(double recallAt5, double recallAt20, double mrr) {
    }

    /**
     * 生成层指标。四个值都可为 null —— null 表示"该指标还没法算",不是"算出来是 0"。
     */
    public record Generation(
            Double citationPrecision,
            Double answerSupportRate,
            Double refusalAccuracy,
            Double falseRefusalRate) {
    }

    /** 没通过的用例与原因。失败明细比分数重要:分数告诉你"变差了多少",明细告诉你"哪里差"。 */
    public record FailedCase(String id, String question, String reason) {
    }

    /** 手写 JSON:字段少、结构固定,手写能精确控制空值与小数位,也免去序列化框架的版本差异。 */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"run_at\": ").append(quote(runAt)).append(",\n");
        sb.append("  \"retriever\": ").append(quote(retrieverName)).append(",\n");
        sb.append("  \"case_count\": ").append(caseCount).append(",\n");
        sb.append("  \"evaluable_cases\": ").append(evaluableCases).append(",\n");
        sb.append("  \"retrieval\": {\n");
        sb.append("    \"recall_at_5\": ").append(dec(retrieval.recallAt5())).append(",\n");
        sb.append("    \"recall_at_20\": ").append(dec(retrieval.recallAt20())).append(",\n");
        sb.append("    \"mrr\": ").append(dec(retrieval.mrr())).append("\n");
        sb.append("  },\n");
        sb.append("  \"generation\": {\n");
        sb.append("    \"citation_precision\": ").append(numOrNull(generation.citationPrecision())).append(",\n");
        sb.append("    \"answer_support_rate\": ").append(numOrNull(generation.answerSupportRate())).append(",\n");
        sb.append("    \"refusal_accuracy\": ").append(numOrNull(generation.refusalAccuracy())).append(",\n");
        sb.append("    \"false_refusal_rate\": ").append(numOrNull(generation.falseRefusalRate())).append("\n");
        sb.append("  },\n");
        sb.append("  \"generation_note\": ").append(quote(generationNote)).append(",\n");
        sb.append("  \"failed_cases\": [");
        for (int i = 0; i < failedCases.size(); i++) {
            FailedCase failed = failedCases.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\"id\": ").append(quote(failed.id()))
                    .append(", \"question\": ").append(quote(failed.question()))
                    .append(", \"reason\": ").append(quote(failed.reason()))
                    .append("}");
        }
        sb.append(failedCases.isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}");
        return sb.toString();
    }

    public static EvalReport of(String retrieverName, EvalDataset dataset,
                                Retrieval retrieval, Generation generation, String generationNote,
                                List<FailedCase> failedCases) {
        return new EvalReport(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                retrieverName, dataset.cases().size(), dataset.evaluableCount(),
                retrieval, generation, generationNote, List.copyOf(failedCases));
    }

    private static String dec(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String numOrNull(Double value) {
        return value == null ? "null" : dec(value);
    }

    private static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
