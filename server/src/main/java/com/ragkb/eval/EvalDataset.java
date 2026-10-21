package com.ragkb.eval;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一份评估集:从 JSON 文件读入,构造时做完整性校验。
 *
 * 校验放在构造阶段而不是跑批阶段,是因为<b>数据错了比代码错了更难发现</b>:
 * 用例 id 重复会让失败列表指错人,正向用例漏写关键词会让它永远算未命中,
 * 而这两种情况跑批时都不会报错,只会让指标悄悄偏低。
 *
 * JSON 形如:
 * <pre>
 * [{"id":"c01","question":"…","expectedKeywords":["…"],"type":"FACTOID","shouldRefuse":false}]
 * </pre>
 */
public record EvalDataset(List<EvalCase> cases) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public EvalDataset {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("评估集不能为空");
        }
        cases = List.copyOf(cases);
        Set<String> seen = new HashSet<>();
        for (EvalCase evalCase : cases) {
            if (!seen.add(evalCase.id())) {
                throw new IllegalArgumentException("用例 id 重复: " + evalCase.id()
                        + " —— 重复 id 会让失败列表指错用例");
            }
        }
    }

    public static EvalDataset load(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        EvalCase[] parsed = MAPPER.readValue(json, EvalCase[].class);
        return new EvalDataset(List.of(parsed));
    }

    /** 供命令行与测试使用:读不到文件时给一句人话,而不是一句 IOException 堆栈。 */
    public static EvalDataset loadOrFail(Path path) {
        try {
            return load(path);
        } catch (IOException e) {
            throw new UncheckedIOException("读取评估集失败: " + path.toAbsolutePath(), e);
        }
    }

    /** 参与检索指标计算的用例数(文档外负例不计)。 */
    public int evaluableCount() {
        return (int) cases.stream().filter(c -> !c.shouldRefuse()).count();
    }

    /** 拒答类负例数量,后面生成层算拒答准确率要用。 */
    public int refusalCaseCount() {
        return (int) cases.stream().filter(EvalCase::shouldRefuse).count();
    }

    public long countOf(EvalCaseType type) {
        return cases.stream().filter(c -> c.type() == type).count();
    }
}
