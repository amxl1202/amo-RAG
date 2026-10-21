package com.ragkb.embedding;

import com.ragkb.chunk.TokenCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * token 校准工具。
 *
 * 校准出的倍率不会被任何代码自动应用 —— 它是一次性测量,结论要写进文档与常量。
 * 所以这里验证的只是"算得对、异常输入有明确反馈",不验证"倍率会生效"。
 */
class TokenCalibrationTest {

    /** 只回报用量、不关心向量的假客户端。 */
    private static EmbeddingClient clientReturningTokens(int tokens) {
        return new EmbeddingClient() {
            @Override
            public int dimension() {
                return 512;
            }

            @Override
            public String model() {
                return "test-model";
            }

            @Override
            public float[] embed(String text) {
                return new float[512];
            }

            @Override
            public EmbeddingResult embedBatch(List<String> texts) {
                return new EmbeddingResult(List.of(new float[512]), tokens);
            }
        };
    }

    @Test
    @DisplayName("倍率 = 服务端用量 / 本地计数")
    void ratioIsApiOverLocal() {
        TokenCounter counter = new TokenCounter();
        String sample = "合规审计要求每季度进行一次,异常交易需在三个工作日内上报。";
        int local = counter.count(sample);

        TokenCalibration.Result result = TokenCalibration.measure(counter, clientReturningTokens(local * 2), sample);

        assertThat(result.localTokens()).isEqualTo(local);
        assertThat(result.apiTokens()).isEqualTo(local * 2);
        assertThat(result.ratio()).isEqualTo(2.0);
        assertThat(result.describe()).contains("倍率");
    }

    @Test
    @DisplayName("服务端没回报用量时不能算倍率 —— 那种倍率是假的,写进文档会误导")
    void zeroApiTokensIsRejected() {
        TokenCounter counter = new TokenCounter();

        assertThatThrownBy(() -> TokenCalibration.measure(counter, clientReturningTokens(0), "一段文本"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未回报 token 用量");
    }

    @Test
    @DisplayName("空样本无法校准")
    void blankSampleIsRejected() {
        TokenCounter counter = new TokenCounter();

        assertThatThrownBy(() -> TokenCalibration.measure(counter, clientReturningTokens(10), "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
