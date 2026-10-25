package com.ragkb.retrieval;

import com.ragkb.embedding.EmbeddingClient;
import com.ragkb.vector.ChunkVector;
import com.ragkb.vector.ScoredChunk;
import com.ragkb.vector.VectorStoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 语义检索的编排逻辑。
 *
 * 检索层本身很薄,但有两个点必须钉住:<b>限额为零时不该去调外部接口</b>(浪费钱),
 * 以及<b>结果顺序必须原样透传</b>(排序是存储层的职责,这里重排会把分数语义搞乱)。
 */
class VectorRetrieverTest {

    private static final class FakeRepository implements VectorStoreRepository {
        private final List<ScoredChunk> fixed;
        private float[] lastQuery;
        private int lastLimit;

        FakeRepository(List<ScoredChunk> fixed) {
            this.fixed = fixed;
        }

        @Override
        public String model() {
            return "test-model";
        }

        @Override
        public int saveAll(List<ChunkVector> vectors) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ScoredChunk> search(float[] queryVector, int limit, long tenantId) {
            this.lastQuery = queryVector;
            this.lastLimit = limit;
            return fixed;
        }

        @Override
        public long count() {
            return fixed.size();
        }
    }

    /** 记录两侧各被调了几次 —— 检索走错到文档侧方法时,这里会立刻暴露。 */
    private static final class CountingEmbeddingClient implements EmbeddingClient {
        private int queryCalls;
        private int documentCalls;

        @Override
        public int dimension() {
            return 4;
        }

        @Override
        public String model() {
            return "test-model";
        }

        @Override
        public float[] embedQuery(String text) {
            queryCalls++;
            return new float[]{1, 0, 0, 0};
        }

        @Override
        public float[] embedDocument(String text) {
            documentCalls++;
            return new float[]{1, 0, 0, 0};
        }

        @Override
        public EmbeddingResult embedDocuments(List<String> texts) {
            documentCalls++;
            return new EmbeddingResult(List.of(new float[]{1, 0, 0, 0}), 1);
        }
    }

    private static ScoredChunk chunk(long id, String content, double score) {
        return new ScoredChunk(id, 1L, content, "第一章", 0, content.length(), null, null, score);
    }

    @Test
    @DisplayName("把问题转成向量后交给存储层,并原样透传条数限制")
    void delegatesToRepositoryWithLimit() {
        FakeRepository repository = new FakeRepository(List.of(chunk(1, "甲", 0.9), chunk(2, "乙", 0.8)));
        VectorRetriever retriever = new VectorRetriever(new CountingEmbeddingClient(), repository);

        List<ScoredChunk> result = retriever.search("问题", 7, 1L);

        assertThat(result).hasSize(2);
        assertThat(repository.lastLimit).isEqualTo(7);
        assertThat(repository.lastQuery).containsExactly(1.0f, 0.0f, 0.0f, 0.0f);
    }

    @Test
    @DisplayName("结果顺序不重排 —— 排序是存储层的职责,这里动一下就破坏了分数语义")
    void preservesRepositoryOrder() {
        FakeRepository repository = new FakeRepository(List.of(chunk(9, "后出现的", 0.1), chunk(3, "先出现的", 0.95)));
        VectorRetriever retriever = new VectorRetriever(new CountingEmbeddingClient(), repository);

        List<ScoredChunk> result = retriever.search("问题", 5, 1L);

        assertThat(result).extracting(ScoredChunk::chunkId).containsExactly(9L, 3L);
    }

    @Test
    @DisplayName("限额小于等于 0 时直接返回空,不花一次向量化调用")
    void zeroLimitSkipsEmbeddingCall() {
        CountingEmbeddingClient client = new CountingEmbeddingClient();
        VectorRetriever retriever = new VectorRetriever(client, new FakeRepository(List.of()));

        assertThat(retriever.search("问题", 0, 1L)).isEmpty();
        assertThat(client.queryCalls).as("不该调用向量化接口").isZero();
    }

    @Test
    @DisplayName("空问题直接拒绝,避免把空串算成向量去检索")
    void blankQuestionIsRejected() {
        VectorRetriever retriever = new VectorRetriever(new CountingEmbeddingClient(), new FakeRepository(List.of()));

        assertThatThrownBy(() -> retriever.search("   ", 5, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("searchContents 只取正文,便于直接接评估跑批")
    void searchContentsExtractsContent() {
        FakeRepository repository = new FakeRepository(List.of(chunk(1, "第一段", 0.9), chunk(2, "第二段", 0.8)));
        VectorRetriever retriever = new VectorRetriever(new CountingEmbeddingClient(), repository);

        List<String> contents = retriever.searchContents("问题", 5, 1L);

        assertThat(contents).containsExactly("第一段", "第二段");
    }

    @Test
    @DisplayName("向量维度过长或过短时存储层会报错,检索层不吞掉这个错误")
    void repositoryErrorPropagates() {
        VectorStoreRepository broken = new VectorStoreRepository() {
            @Override
            public String model() {
                return "test-model";
            }

            @Override
            public int saveAll(List<ChunkVector> vectors) {
                return 0;
            }

            @Override
            public List<ScoredChunk> search(float[] queryVector, int limit, long tenantId) {
                throw new IllegalStateException("dimension mismatch");
            }

            @Override
            public long count() {
                return 0;
            }
        };
        VectorRetriever retriever = new VectorRetriever(new CountingEmbeddingClient(), broken);

        assertThatThrownBy(() -> retriever.search("问题", 5, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    @DisplayName("空库返回空列表,不抛异常")
    void emptyStoreReturnsEmptyList() {
        VectorRetriever retriever = new VectorRetriever(new CountingEmbeddingClient(),
                new FakeRepository(new ArrayList<>()));

        assertThat(retriever.search("问题", 5, 1L)).isEmpty();
    }

    @Test
    @DisplayName("检索走的是查询侧向量,不是入库用的文档侧")
    void usesQuerySideEmbedding() {
        CountingEmbeddingClient client = new CountingEmbeddingClient();
        VectorRetriever retriever = new VectorRetriever(client,
                new FakeRepository(List.of(chunk(1, "甲", 0.9))));

        retriever.search("问题", 5, 1L);

        assertThat(client.queryCalls).isEqualTo(1);
        assertThat(client.documentCalls)
                .as("查询侧与文档侧不是同一分布,调错会让相似度整体失真")
                .isZero();
    }
}
