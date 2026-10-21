package com.ragkb.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评估集本身的校验。
 *
 * 这一组测试针对的是"数据错了比代码错了更难发现"这件事:用例 id 重复会让失败列表指错人,
 * 正向用例漏写关键词会让它永远算未命中 —— 两种情况跑批时都不报错,只会让指标悄悄偏低。
 */
class EvalDatasetTest {

    private static EvalCase factoid(String id, String question, String... keywords) {
        return new EvalCase(id, question, List.of(keywords), EvalCaseType.FACTOID, false);
    }

    private static EvalCase outOfScope(String id, String question) {
        return new EvalCase(id, question, List.of(), EvalCaseType.OUT_OF_SCOPE, true);
    }

    @Test
    @DisplayName("用例 id 重复时拒绝加载,否则失败列表会指向错误的用例")
    void duplicateIdsAreRejected() {
        assertThatThrownBy(() -> new EvalDataset(List.of(
                factoid("c01", "问题一", "甲"),
                factoid("c01", "问题二", "乙"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id 重复");
    }

    @Test
    @DisplayName("题型是文档外却标了不拒答,属于自相矛盾的用例")
    void outOfScopeMustBeMarkedAsRefuse() {
        assertThatThrownBy(() -> new EvalCase("c09", "文档里有服务器型号吗", List.of(),
                EvalCaseType.OUT_OF_SCOPE, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shouldRefuse");
    }

    @Test
    @DisplayName("正向用例没有期望词就等于没有判据")
    void positiveCaseRequiresKeywords() {
        assertThatThrownBy(() -> new EvalCase("c01", "问题", List.of(), EvalCaseType.FACTOID, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedKeywords 不能为空");
    }

    @Test
    @DisplayName("空白问题与空数据集都应被拒绝")
    void blankQuestionAndEmptyDatasetAreRejected() {
        assertThatThrownBy(() -> new EvalCase("c01", "   ", List.of("甲"), EvalCaseType.FACTOID, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalDataset(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("匹配要求所有期望词同时出现,不是任意一个")
    void matchesRequiresAllKeywords() {
        EvalCase evalCase = factoid("c05", "两路分数为什么不能相加", "量纲完全不同", "归一化");

        assertThat(evalCase.matches("两路分数的量纲完全不同,所以要先归一化")).isTrue();
        // 只缺一个词就不算命中 —— 注意夹具里不能再出现"归一化"这三个字,否则会被自己误判
        assertThat(evalCase.matches("这一段的量纲完全不同,原因是分数尺度不一样")).isFalse();
        assertThat(evalCase.matches(null)).isFalse();
    }

    @Test
    @DisplayName("能从 JSON 文件读回用例,并区分正向与负例")
    void loadsFromJson(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("cases.json");
        Files.writeString(file, """
                [
                  {"id":"c01","question":"固定长度的问题是什么","expectedKeywords":["断点"],"type":"FACTOID","shouldRefuse":false},
                  {"id":"c02","question":"服务器型号","expectedKeywords":[],"type":"OUT_OF_SCOPE","shouldRefuse":true}
                ]
                """, StandardCharsets.UTF_8);

        EvalDataset dataset = EvalDataset.load(file);

        assertThat(dataset.cases()).hasSize(2);
        assertThat(dataset.evaluableCount()).isEqualTo(1);
        assertThat(dataset.refusalCaseCount()).isEqualTo(1);
        assertThat(dataset.countOf(EvalCaseType.OUT_OF_SCOPE)).isEqualTo(1);
        assertThat(dataset.cases().get(0).type()).isEqualTo(EvalCaseType.FACTOID);
        assertThat(dataset.cases().get(1).shouldRefuse()).isTrue();
    }

    @Test
    @DisplayName("仓库里的实际数据集必须能被加载且校验通过")
    void repositoryDatasetIsValid() throws IOException {
        Path path = Path.of("..", "samples", "eval", "cases.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(path), "数据集不存在时跳过");

        EvalDataset dataset = EvalDataset.load(path);

        assertThat(dataset.cases()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(dataset.evaluableCount()).isGreaterThanOrEqualTo(8);
        assertThat(dataset.refusalCaseCount()).isGreaterThanOrEqualTo(2);
        // 四类题型都要覆盖,否则某一层的退化在指标里看不出来
        for (EvalCaseType type : EvalCaseType.values()) {
            assertThat(dataset.countOf(type)).as("题型 %s 至少要有 1 条用例", type).isPositive();
        }
    }
}
