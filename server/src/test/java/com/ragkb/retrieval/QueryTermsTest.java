package com.ragkb.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽词规则。
 *
 * 这一层决定了关键词通道的召回上限:抽不出"断点"这种词,后面打分再准也没用。
 * 断言的重点不是"抽得漂亮",而是几条会直接影响检索的规则:
 * 中文必须切 2-gram(1-gram 太宽会淹没排序)、英文整词必须保留(型号/错误码)、
 * 疑问词必须滤掉(否则每条问题都会命中一堆无关片段)。
 */
class QueryTermsTest {

    @Test
    @DisplayName("中文按 2-gram 切,且不产生单字词")
    void splitsChineseIntoBigrams() {
        List<String> terms = QueryTerms.extract("重叠区间的作用");

        assertThat(terms).contains("重叠", "叠区", "区间", "间的", "的作", "作用");
        assertThat(terms).allSatisfy(term -> assertThat(term.length()).isGreaterThanOrEqualTo(2));
    }

    @Test
    @DisplayName("英文与数字整串保留 —— 型号和错误码拆开就没意义了")
    void keepsAlphanumericTokensWhole() {
        List<String> terms = QueryTerms.extract("HNSW 和 IVFFlat 有什么区别,例如 ERR_4021");

        assertThat(terms).contains("HNSW", "IVFFlat");
        // 下划线被当作分隔符,数字部分仍整串保留
        assertThat(terms.stream().anyMatch(term -> term.equals("ERR") || term.equals("4021")))
                .as("英文片段应整串保留,不应被拆成单个字母")
                .isTrue();
    }

    @Test
    @DisplayName("疑问词被过滤,避免每条问题都命中无关片段")
    void dropsInterrogatives() {
        List<String> terms = QueryTerms.extract("这个为什么是什么");

        assertThat(terms).doesNotContain("什么", "为什", "么样", "这个");
    }

    @Test
    @DisplayName("标点与空白不进入词表")
    void punctuationIsNotATerm() {
        List<String> terms = QueryTerms.extract("切片，，。 向量？？");

        assertThat(terms).contains("切片", "向量");
        assertThat(terms).noneMatch(term -> term.contains("，") || term.contains("？") || term.isBlank());
    }

    @Test
    @DisplayName("词数有上限,避免正则在长问题下退化成全表扫描")
    void capsTermCount() {
        String longQuestion = "切片策略检索链路向量索引融合排序生成引用拒答判定评估指标参数取值"
                + "重叠区间章节继承目标合并硬切边界向量维度距离度量倒排融合平滑常数";

        List<String> terms = QueryTerms.extract(longQuestion);

        assertThat(terms).hasSizeLessThanOrEqualTo(16);
        assertThat(terms).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("空输入与纯标点返回空词表")
    void emptyInputYieldsNoTerms() {
        assertThat(QueryTerms.extract(null)).isEmpty();
        assertThat(QueryTerms.extract("   ")).isEmpty();
        assertThat(QueryTerms.extract("，。？！")).isEmpty();
    }

    @Test
    @DisplayName("正则拼接不引入未转义的元字符")
    void regexContainsOnlySafeCharacters() {
        String pattern = QueryTerms.toRegex(QueryTerms.extract("HNSW 与 IVFFlat 的差别"));

        assertThat(pattern).doesNotContain("(", ")", "[", "]", "\\", ".", "*", "+", "?");
    }
}
