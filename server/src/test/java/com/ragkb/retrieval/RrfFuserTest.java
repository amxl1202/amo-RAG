package com.ragkb.retrieval;

import com.ragkb.vector.ScoredChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * RRF 融合的数学。
 *
 * 用"构造已知名次 → 手算分数"的方式验证,而不是跑真实检索看数字像不像:
 * 融合公式一旦写错,真实数据上只会给出一个"看起来合理"的错排序。
 */
class RrfFuserTest {

    private static ScoredChunk chunk(long id, double score) {
        return new ScoredChunk(id, 1L, "内容" + id, "第一章", 0, 10, null, null, score);
    }

    @Test
    @DisplayName("两路都命中同一条时,分数是两侧贡献之和")
    void bothChannelsAddUp() {
        List<ScoredChunk> vector = List.of(chunk(1, 0.9));
        List<ScoredChunk> keyword = List.of(chunk(1, 0.8));

        List<RrfFuser.Hit> hits = new RrfFuser().fuse(vector, keyword, 60, 10);

        assertThat(hits).hasSize(1);
        // 两路都是第 1 名:k=60 时每条贡献 1/61
        assertThat(hits.get(0).rrfScore()).isCloseTo(2.0 / 61, within(1e-9));
        assertThat(hits.get(0).vectorRank()).isEqualTo(1);
        assertThat(hits.get(0).keywordRank()).isEqualTo(1);
        assertThat(hits.get(0).fromSingleChannel()).isFalse();
    }

    @Test
    @DisplayName("只在一路出现也保留,分数只算该路 —— 这是双通道的意义所在")
    void singleChannelHitsAreKept() {
        List<ScoredChunk> vector = List.of(chunk(1, 0.9), chunk(2, 0.8));
        List<ScoredChunk> keyword = List.of(chunk(3, 0.7));

        List<RrfFuser.Hit> hits = new RrfFuser().fuse(vector, keyword, 60, 10);

        assertThat(hits).extracting(hit -> hit.chunk().chunkId())
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        RrfFuser.Hit onlyKeyword = hits.stream().filter(h -> h.chunk().chunkId() == 3L).findFirst().orElseThrow();
        assertThat(onlyKeyword.vectorRank()).isZero();
        assertThat(onlyKeyword.fromSingleChannel()).isTrue();
        assertThat(onlyKeyword.rrfScore()).isCloseTo(1.0 / 61, within(1e-9));
    }

    @Test
    @DisplayName("k 越小头部越强势:k=0 时第 1 名是第 2 名的两倍,k=60 时几乎持平")
    void smallerKAmplifiesTheHead() {
        List<ScoredChunk> vector = List.of(chunk(1, 0.9), chunk(2, 0.8));

        List<RrfFuser.Hit> sharp = new RrfFuser().fuse(vector, List.of(), 0, 10);
        List<RrfFuser.Hit> flat = new RrfFuser().fuse(vector, List.of(), 60, 10);

        double sharpRatio = sharp.get(0).rrfScore() / sharp.get(1).rrfScore();
        double flatRatio = flat.get(0).rrfScore() / flat.get(1).rrfScore();

        assertThat(sharpRatio).as("k=0:1/1 对 1/2").isCloseTo(2.0, within(1e-9));
        assertThat(flatRatio).as("k=60:1/61 对 1/62").isCloseTo(62.0 / 61, within(1e-9));
        assertThat(flatRatio).isLessThan(sharpRatio);
    }

    @Test
    @DisplayName("同分时按 chunkId 升序,保证同一查询两次跑顺序一致")
    void tiesBreakDeterministically() {
        // 两条都只在向量通道出现,且名次不同 —— 构造同分的办法是让它们各自在对应通道都是第 1 名
        List<ScoredChunk> vector = List.of(chunk(7, 0.9));
        List<ScoredChunk> keyword = List.of(chunk(3, 0.8));

        List<RrfFuser.Hit> first = new RrfFuser().fuse(vector, keyword, 60, 10);
        List<RrfFuser.Hit> second = new RrfFuser().fuse(vector, keyword, 60, 10);

        assertThat(first).extracting(h -> h.chunk().chunkId()).containsExactly(3L, 7L);
        assertThat(second).extracting(h -> h.chunk().chunkId()).containsExactly(3L, 7L);
    }

    @Test
    @DisplayName("limit 截断,且按融合分降序")
    void limitTruncatesByScore() {
        List<ScoredChunk> vector = List.of(chunk(1, 0.9), chunk(2, 0.8), chunk(3, 0.7));
        List<ScoredChunk> keyword = List.of(chunk(3, 0.9));   // 3 号两路都有,融合分最高

        List<RrfFuser.Hit> hits = new RrfFuser().fuse(vector, keyword, 60, 2);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).chunk().chunkId()).as("两路都命中的应排第一").isEqualTo(3L);
    }

    @Test
    @DisplayName("空输入与非法参数")
    void edgeCases() {
        RrfFuser fuser = new RrfFuser();

        assertThat(fuser.fuse(List.of(), List.of(), 60, 10)).isEmpty();
        assertThat(fuser.fuse(List.of(chunk(1, 0.9)), List.of(), 60, 0)).isEmpty();
        assertThatThrownBy(() -> fuser.fuse(List.of(), List.of(), -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
