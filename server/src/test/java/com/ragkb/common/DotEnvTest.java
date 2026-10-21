package com.ragkb.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * .env 的最小解析。
 *
 * 这一层错了不会报错,只会让某个变量变成"看起来配了其实没配" ——
 * 而它偏偏是密钥与端点的入口,所以边界要钉住。
 */
class DotEnvTest {

    @Test
    @DisplayName("基本键值、空行、整行注释")
    void parsesBasicPairs(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, """
                # 整行注释
                DB_URL=jdbc:postgresql://localhost:5433/rag_kb

                CHAT_MODEL=qwen-plus
                """, StandardCharsets.UTF_8);

        Map<String, String> env = DotEnv.load(file);

        assertThat(env).hasSize(2)
                .containsEntry("DB_URL", "jdbc:postgresql://localhost:5433/rag_kb")
                .containsEntry("CHAT_MODEL", "qwen-plus");
    }

    @Test
    @DisplayName("行尾注释被截掉 —— 否则 ' # 备注' 会被当成值的一部分")
    void stripsInlineComment(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, "CHAT_MODEL=qwen-plus  # 对话模型\n", StandardCharsets.UTF_8);

        assertThat(DotEnv.load(file)).containsEntry("CHAT_MODEL", "qwen-plus");
    }

    @Test
    @DisplayName("引号内的 # 原样保留,紧贴的 # 也不算注释")
    void keepsHashInsideQuotesAndTightHash(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, """
                JWT_SECRET="abc # def"
                URL=http://x/y#frag
                """, StandardCharsets.UTF_8);

        assertThat(DotEnv.load(file))
                .containsEntry("JWT_SECRET", "abc # def")
                .containsEntry("URL", "http://x/y#frag");
    }

    @Test
    @DisplayName("文件不存在时返回空表,不抛异常")
    void missingFileYieldsEmptyMap(@TempDir Path dir) {
        assertThat(DotEnv.load(dir.resolve("nope.env"))).isEmpty();
    }
}
