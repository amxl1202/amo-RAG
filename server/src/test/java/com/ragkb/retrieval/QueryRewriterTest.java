package com.ragkb.retrieval;

import com.ragkb.generation.ChatException;
import com.ragkb.generation.ChatModel;
import com.ragkb.generation.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询改写。
 *
 * <h2>两条底线</h2>
 * 1. 改写是<b>增强</b>:模型出错时必须退回原问题,不能让问答失败;
 * 2. 模型输出经常带围栏、编号、解释 —— 清洗必须挡住,否则关键词通道会拿到一整段话。
 */
class QueryRewriterTest {

    private static ChatModel model(String output) {
        return (system, user) -> new ChatResponse(output, "fake", 10, 5, 1);
    }

    @Test
    @DisplayName("正常改写:取模型给的关键词串")
    void rewritesToKeywords() {
        QueryRewriter rewriter = new QueryRewriter(model("HNSW IVFFlat 训练 差别"));

        assertThat(rewriter.rewrite("这两种索引的核心差别到底在哪里?")).isEqualTo("HNSW IVFFlat 训练 差别");
    }

    @Test
    @DisplayName("模型套代码块、写成编号列表时也能清洗出关键词")
    void cleansFencedAndNumberedOutput() {
        QueryRewriter fenced = new QueryRewriter(model("```\n重叠量 阈值\n```"));
        assertThat(fenced.rewrite("重叠量取多少")).isEqualTo("重叠量 阈值");

        QueryRewriter numbered = new QueryRewriter(model("1. RRF 融合 k 值"));
        assertThat(numbered.rewrite("k 取多少")).isEqualTo("RRF 融合 k 值");
    }

    @Test
    @DisplayName("改写失败时退回原问题 —— 增强环节不能把问答带崩")
    void fallsBackToOriginalOnFailure() {
        ChatModel broken = (system, user) -> {
            throw new ChatException("模型挂了", false);
        };
        QueryRewriter rewriter = new QueryRewriter(broken);

        assertThat(rewriter.rewrite("原问题")).isEqualTo("原问题");
    }

    @Test
    @DisplayName("输出为空或只有空白时也退回原问题")
    void fallsBackWhenOutputBlank() {
        assertThat(new QueryRewriter(model("   ")).rewrite("原问题")).isEqualTo("原问题");
        assertThat(new QueryRewriter(model("```\n```")).rewrite("原问题")).isEqualTo("原问题");
    }

    @Test
    @DisplayName("超长输出被截断,不会把整段解释塞进关键词通道")
    void truncatesOverlongOutput() {
        QueryRewriter rewriter = new QueryRewriter(model("词".repeat(400)));

        assertThat(rewriter.rewrite("问题")).hasSizeLessThanOrEqualTo(120);
    }

    @Test
    @DisplayName("提示词里必须包含'关键词'与'不要输出解释'两条约束")
    void promptStatesConstraints() {
        List<String> prompts = new ArrayList<>();
        ChatModel recording = (system, user) -> {
            prompts.add(system);
            return new ChatResponse("词", "fake", 1, 1, 1);
        };

        new QueryRewriter(recording).rewrite("问题");

        assertThat(prompts).hasSize(1);
        QueryRewriter.requiredInstructionKeywords()
                .forEach(keyword -> assertThat(prompts.get(0)).contains(keyword));
    }
}
