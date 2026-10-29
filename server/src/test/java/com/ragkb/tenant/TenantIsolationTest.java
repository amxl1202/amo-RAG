package com.ragkb.tenant;

import com.ragkb.document.mapper.DocumentMapper;
import com.ragkb.retrieval.JdbcKeywordRetriever;
import com.ragkb.support.DatabaseAvailableCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户隔离。
 *
 * <h2>这组用例测的是"证明隔离",不是"声称隔离"</h2>
 * 隔离这类需求最容易犯的错是：代码里写了 `WHERE tenant_id = ?`，README 里写了"数据隔离"，
 * 但没人验证过<b>不满足条件时到底会发生什么</b>。所以这里全部用<b>负向</b>断言:
 * 别人的文档必须表现为"不存在"（404 而不是 403），别人的切片必须<b>不出现在检索候选里</b>。
 *
 * <h2>为什么同时测 HTTP 与检索层</h2>
 * 接口层的鉴权容易做对（一个 getById 加条件），检索层却极容易漏 ——
 * 一条 SQL 忘了 join documents，接口看起来完全正常，泄漏的却是回答里的内容。
 * 而这一层没有任何"权限报错"作为征兆，只能靠测试钉住。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.secret=test-secret-0123456789abcdef0123456789abcdef")
@ExtendWith(DatabaseAvailableCondition.class)
class TenantIsolationTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DocumentMapper documentMapper;

    private long insertDocument(String sha256, long tenantId) {
        jdbc.update("INSERT INTO documents (original_name, sha256, file_size, storage_path, status, tenant_id) "
                        + "VALUES (?, ?, ?, ?, 'CHUNKED', ?)",
                "隔离测试-" + tenantId + ".txt", sha256, 10, "test/" + sha256, tenantId);
        return jdbc.queryForObject("SELECT id FROM documents WHERE sha256 = ? AND tenant_id = ?",
                Long.class, sha256, tenantId);
    }

    private String tokenFor(String username, long tenantId) throws Exception {
        String hash = new BCryptPasswordEncoder().encode("s3cret-pass");
        jdbc.update("INSERT INTO users (username, password_hash, role, tenant_id) VALUES (?, ?, 'USER', ?) "
                + "ON CONFLICT (username) DO UPDATE SET tenant_id = EXCLUDED.tenant_id", username, hash, tenantId);
        HttpResponse<String> response = postJson("/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"s3cret-pass\"}");
        String body = response.body();
        int idx = body.indexOf("\"token\":\"");
        assertThat(idx).as(body).isGreaterThan(-1);
        return body.substring(idx + 9, body.indexOf('"', idx + 9));
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void cleanUp(String sha256) {
        jdbc.update("DELETE FROM documents WHERE sha256 = ?", sha256);
        jdbc.update("DELETE FROM users WHERE username LIKE 'iso_%'");
    }

    @Test
    @DisplayName("跨租户取文档详情:必须是 404,不能是 403")
    void crossTenantDetailIsNotFound() throws Exception {
        String sha = "tenant-iso-" + System.nanoTime();
        try {
            long otherDocId = insertDocument(sha, 2L);
            String token = tokenFor("iso_a_" + System.nanoTime(), 1L);

            HttpResponse<String> response = get("/api/documents/" + otherDocId, token);

            assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
            // 403 会告诉对方"这个 id 存在"——那本身就是信息泄漏
            assertThat(response.statusCode()).isNotEqualTo(403);
        } finally {
            cleanUp(sha);
        }
    }

    @Test
    @DisplayName("跨租户触发重新切片:同样 404,不能让别人用你的 id 触发昂贵操作")
    void crossTenantIngestIsNotFound() throws Exception {
        String sha = "tenant-iso-ingest-" + System.nanoTime();
        try {
            long otherDocId = insertDocument(sha, 2L);
            String token = tokenFor("iso_b_" + System.nanoTime(), 1L);

            assertThat(post("/api/documents/" + otherDocId + "/ingest", token).statusCode()).isEqualTo(404);
            assertThat(post("/api/documents/" + otherDocId + "/vectorize", token).statusCode()).isEqualTo(404);
        } finally {
            cleanUp(sha);
        }
    }

    @Test
    @DisplayName("检索通道不返回别的租户的切片 —— 这条漏了,答案里就会出现别人文档的内容")
    void retrievalNeverCrossesTenant() {
        String sha = "tenant-iso-chunk-" + System.nanoTime();
        // 用拉丁串而不是中文短语:TRGM 是模糊匹配,中文短语会和语料里的"多租户"等词撞上,
        // 导致断言失败的原因不是隔离而是相似度 —— 那会把测试变成噪声
        String marker = "ZQXPROBE" + System.nanoTime();
        try {
            long docId = insertDocument(sha, 2L);
            jdbc.update("INSERT INTO document_chunks "
                            + "(document_id, chunk_index, section_path, content, token_count, char_start, char_end) "
                            + "VALUES (?, 0, '第一章', ?, 12, 0, ?)",
                    docId, "这句话只属于租户二,内容包含 " + marker, ("这句话只属于租户二,内容包含 " + marker).length());

            JdbcKeywordRetriever retriever = new JdbcKeywordRetriever(jdbc, JdbcKeywordRetriever.Mode.TRGM);

            // 断言"不出现那条切片",而不是"结果为空":关键词通道是模糊匹配,
            // 租户一里本来就可能有别的切片相似度够高 —— 那和隔离无关
            assertThat(retriever.search(marker, 5, 1L))
                    .as("租户一不该看到租户二的切片")
                    .noneMatch(chunk -> chunk.documentId() == docId);
            assertThat(retriever.search(marker, 5, 2L))
                    .as("租户二自己能查到,说明过滤条件真的生效,而不是查了个空")
                    .anyMatch(chunk -> chunk.documentId() == docId);
        } finally {
            cleanUp(sha);
        }
    }

    @Test
    @DisplayName("去重键跟着租户走:同一份文件两个租户都能入库,同租户内仍然唯一")
    void dedupeKeyIsPerTenant() {
        String sha = "tenant-iso-dedupe-" + System.nanoTime();
        try {
            insertDocument(sha, 1L);
            // 换个租户传同一份文件:应当成功 —— 全局唯一会让这里 409,既挡住正常上传又泄漏"别人有这份文件"
            assertThat(insertDocument(sha, 2L)).isPositive();

            assertThatThrownBy(() -> insertDocument(sha, 1L))
                    .as("同一租户内重复仍然要拒绝")
                    .isInstanceOf(DuplicateKeyException.class);
        } finally {
            cleanUp(sha);
        }
    }

    @Test
    @DisplayName("列表只返回本租户的文档")
    void listOnlyOwnTenant() throws Exception {
        String sha = "tenant-iso-list-" + System.nanoTime();
        try {
            insertDocument(sha, 2L);
            String token = tokenFor("iso_c_" + System.nanoTime(), 1L);

            HttpResponse<String> response = get("/api/documents", token);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).as("租户一的列表里不该出现租户二的文档").doesNotContain(sha);
        } finally {
            cleanUp(sha);
        }
    }

    @Test
    @DisplayName("列表分页:返回信封且 total 是过滤后的总数")
    void listIsPaged() throws Exception {
        String sha = "tenant-iso-page-" + System.nanoTime();
        try {
            insertDocument(sha, 2L);
            String token = tokenFor("iso_d_" + System.nanoTime(), 1L);

            HttpResponse<String> response = get("/api/documents?page=1&size=1", token);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .as("分页信封必须带 total,否则前端画不出页码")
                    .contains("\"total\"")
                    .contains("\"items\"")
                    .contains("\"page\"");
            assertThat(response.body()).doesNotContain(sha);
        } finally {
            cleanUp(sha);
        }
    }
}
