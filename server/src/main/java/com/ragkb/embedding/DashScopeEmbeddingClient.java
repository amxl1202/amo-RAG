package com.ragkb.embedding;

import com.ragkb.common.http.HttpPoster;
import com.ragkb.common.http.JdkHttpPoster;
import com.ragkb.common.http.RetryPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope 文本向量化客户端。
 *
 * 三件容易做错、这里刻意做对的事:
 * <ol>
 *   <li><b>按 text_index 重排</b>。服务端返回顺序不保证与入参一致。不重排的话,
 *       向量会和切片错配 —— 而且不会报错,只会让检索结果莫名其妙地差。</li>
 *   <li><b>维度校验</b>。模型的维度必须与向量表 DDL 一致,不一致在入库时才会炸,
 *       那时已经花掉了调用成本。这里提前拦。</li>
 *   <li><b>只重试值得重试的</b>。5xx / 429 / 网络异常重试;4xx(参数错、Key 错)重试
 *       只是浪费时间,还会把真正的错误原因埋在重试日志里。</li>
 * </ol>
 */
public class DashScopeEmbeddingClient implements EmbeddingClient {

    private static final String AUTH_HEADER = "Authorization";
    /** 非对称检索的两侧类型:查询是短问题,文档是整段正文。 */
    private static final String TEXT_TYPE_QUERY = "query";
    private static final String TEXT_TYPE_DOCUMENT = "document";
    private static final int MAX_REPORTED_BODY = 300;

    private final EmbeddingProperties properties;
    private final HttpPoster poster;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public DashScopeEmbeddingClient(EmbeddingProperties properties) {
        this(properties, new JdkHttpPoster(properties.timeoutSeconds()));
    }

    public DashScopeEmbeddingClient(EmbeddingProperties properties, HttpPoster poster) {
        this.properties = properties;
        this.poster = poster;
    }

    @Override
    public int dimension() {
        return properties.dimension();
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public float[] embedQuery(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("待向量化的文本不能为空");
        }
        return callWithRetry(List.of(text), TEXT_TYPE_QUERY).vectors().get(0);
    }

    @Override
    public float[] embedDocument(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("待向量化的文本不能为空");
        }
        return callWithRetry(List.of(text), TEXT_TYPE_DOCUMENT).vectors().get(0);
    }

    @Override
    public EmbeddingResult embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingResult(List.of(), 0);
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("批量向量化时发现空白文本,拒绝发送");
            }
        }
        List<float[]> all = new ArrayList<>(texts.size());
        int totalTokens = 0;
        // 接口对单次批量有上限,超了整批被拒,所以这里按 batchSize 切片
        for (int from = 0; from < texts.size(); from += properties.batchSize()) {
            int to = Math.min(texts.size(), from + properties.batchSize());
            EmbeddingResult part = callWithRetry(texts.subList(from, to), TEXT_TYPE_DOCUMENT);
            all.addAll(part.vectors());
            totalTokens += part.totalTokens();
        }
        return new EmbeddingResult(all, totalTokens);
    }

    private EmbeddingResult callWithRetry(List<String> slice, String textType) {
        // 放在唯一的外部调用入口上:三个公开方法(单条查询 / 单条文档 / 批量文档)都经过这里,
        // 只写在批量方法里会让单条调用先打一个必然 401 的请求
        requireApiKey();
        String requestBody = buildRequestBody(slice, textType);
        String lastError = "未知错误";
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                HttpPoster.Response response = poster.post(properties.endpoint(), authHeaders(), requestBody);
                if (response.status() == 200) {
                    return parseResponse(response.body(), slice.size());
                }
                if (!RetryPolicy.isRetryableStatus(response.status())) {
                    throw new EmbeddingException("向量化接口返回 " + response.status()
                            + ",属于不可重试的错误: " + abbreviate(response.body()), false);
                }
                lastError = "HTTP " + response.status() + ": " + abbreviate(response.body());
            } catch (EmbeddingException e) {
                if (!e.retryable()) {
                    throw e;
                }
                lastError = e.getMessage();
            } catch (InterruptedException e) {
                // 收到中断就退出,并把中断标志还回去,而不是吞掉当普通失败重试
                Thread.currentThread().interrupt();
                throw new EmbeddingException("向量化被中断", false, e);
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (attempt < properties.maxAttempts() && !RetryPolicy.sleepBeforeRetry(attempt)) {
                // 退避等待时被中断:直接退出,而不是"恢复了中断标志还继续发下一次请求"。
                // 后者会让关闭/超时路径下多发一次外部调用,语义不可预期。
                throw new EmbeddingException("向量化被中断", false);
            }
        }
        throw new EmbeddingException("向量化失败(已尝试 " + properties.maxAttempts() + " 次): " + lastError, true);
    }

    private void requireApiKey() {
        if (!properties.hasApiKey()) {
            throw new EmbeddingException("缺少 DASHSCOPE_API_KEY:向量化需要调用外部接口,请先在环境变量里配置", false);
        }
    }

    private Map<String, String> authHeaders() {
        return Map.of(AUTH_HEADER, "Bearer " + properties.apiKey(), "Content-Type", "application/json");
    }

    private String buildRequestBody(List<String> slice, String textType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.model());
        payload.put("input", Map.of("texts", slice));
        // text_type 放在 parameters 里,两侧都显式给,不依赖服务端默认值。
        // 实测(2026-10-21,专属实例 text-embedding-v3):query 与 document 输出的向量余弦为
        // 1.000000 —— 参数被接受但不改变结果。所以这一行现在没有可测量的收益,
        // 它防的是"换模型/换供应商后默认值变了,而调用方不知道"。
        payload.put("parameters", Map.of("dimension", properties.dimension(), "text_type", textType));
        return mapper.writeValueAsString(payload);
    }

    private EmbeddingResult parseResponse(String body, int expectedCount) {
        JsonNode root = mapper.readTree(body);
        JsonNode array = path(root, "output", "embeddings");
        if (array == null || !array.isArray()) {
            throw new EmbeddingException("响应缺少 output.embeddings: " + abbreviate(body), false);
        }
        if (array.size() != expectedCount) {
            throw new EmbeddingException("返回向量条数 " + array.size() + " 与请求条数 "
                    + expectedCount + " 不一致 —— 直接使用会导致向量与切片错配", false);
        }
        float[][] ordered = new float[expectedCount][];
        boolean[] seen = new boolean[expectedCount];
        for (JsonNode item : array) {
            JsonNode indexNode = item.get("text_index");
            JsonNode vectorNode = item.get("embedding");
            if (indexNode == null || vectorNode == null) {
                throw new EmbeddingException("响应项缺少 text_index 或 embedding", false);
            }
            int index = indexNode.asInt();
            if (index < 0 || index >= expectedCount) {
                throw new EmbeddingException("text_index 越界: " + index, false);
            }
            if (seen[index]) {
                throw new EmbeddingException("text_index 重复: " + index, false);
            }
            if (vectorNode.size() != properties.dimension()) {
                throw new EmbeddingException("向量维度不符: 期望 " + properties.dimension()
                        + ",实得 " + vectorNode.size() + " —— 与向量表 DDL 不一致,入库会失败", false);
            }
            float[] vector = new float[vectorNode.size()];
            for (int i = 0; i < vectorNode.size(); i++) {
                JsonNode element = vectorNode.get(i);
                // 只挡 size 挡不住畸形元素:数组里混进 null 或字符串时会直接 NPE 或被静默转成 0
                if (element == null || !element.isNumber()) {
                    throw new EmbeddingException("向量第 " + i + " 维不是数字,响应畸形", false);
                }
                vector[i] = (float) element.asDouble();
            }
            ordered[index] = vector;
            seen[index] = true;
        }
        for (int i = 0; i < expectedCount; i++) {
            if (!seen[i]) {
                throw new EmbeddingException("缺少 text_index=" + i + " 的向量", false);
            }
        }
        int totalTokens = 0;
        JsonNode usage = root.get("usage");
        if (usage != null && usage.get("total_tokens") != null) {
            totalTokens = usage.get("total_tokens").asInt();
        }
        return new EmbeddingResult(List.of(ordered), totalTokens);
    }

    private static JsonNode path(JsonNode root, String... keys) {
        JsonNode current = root;
        for (String key : keys) {
            if (current == null) {
                return null;
            }
            current = current.get(key);
        }
        return current;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_REPORTED_BODY ? body : body.substring(0, MAX_REPORTED_BODY) + "…";
    }
}
