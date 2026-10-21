package com.ragkb.embedding;

import java.util.List;

/**
 * 向量化能力:文本进,向量出。
 *
 * 刻意<b>不</b>暴露 HTTP 细节,也不依赖 Spring —— 上层只关心"给我向量",
 * 换模型或换供应商时只改实现类,检索与入库逻辑不动。
 *
 * <h2>为什么查询与文档要分成两个方法</h2>
 * 检索是<b>非对称</b>任务:查询是一句短问题,文档是一整段正文,两者的向量分布本就不同。
 * 通义官方文档明确建议区分 {@code text_type=query} 与 {@code text_type=document} ——
 * 查询侧生成"标题式"向量,方向性更强;文档侧生成"正文式"向量,信息更全。
 * 两边都用默认值(文档型)时,短查询对长文档的匹配精度会下降。
 *
 * 这个差别不报错,只让 recall / MRR 偏低几个点,而指标偏低最容易被误读成
 * "这个检索方案不行"。所以接口层面就把两侧分开:调错方法在编译期就暴露,
 * 不依赖调用方记得传参数。
 *
 * <h2>实测:当前实例上这个参数没有生效</h2>
 * 用专属实例的 {@code text-embedding-v3} 对同一段文本分别取
 * {@code text_type=query} / {@code document} / 不传,三者向量的余弦相似度
 * <b>全部为 1.000000</b>,前三维逐位相同。也就是说服务端接受这个参数(HTTP 200)但不改变输出。
 * 因此:
 * <ul>
 *   <li>接口分成两侧仍然要做 —— 换模型、换供应商,或服务端开始真正区分时,调用方不用改;</li>
 *   <li>但<b>不要声称这个改动提升了检索指标</b>:在当前实例上它是零效果,
 *       现有向量的口径也没有被削弱,不需要重刷、不需要重跑基线。</li>
 * </ul>
 */
public interface EmbeddingClient {

    /** 向量维度,必须与向量表 DDL 一致。 */
    int dimension();

    /**
     * 产出向量的模型名。
     *
     * 入库时要写进 {@code chunk_embeddings.model},检索时要靠它限定范围 ——
     * 换模型重刷期间,新旧向量同库共存,混在一起排序会让结果失真。
     */
    String model();

    /**
     * 单条<b>查询</b>向量化。
     *
     * 只给用户问题用。入库切片请用 {@link #embedDocument} / {@link #embedDocuments} ——
     * 两侧的向量不在同一分布上,拿查询侧去入库会让相似度整体失真。
     */
    float[] embedQuery(String text);

    /** 单条<b>文档</b>向量化(入库用)。 */
    float[] embedDocument(String text);

    /**
     * 批量<b>文档</b>向量化(入库用)。
     *
     * 返回的向量顺序与入参<b>严格一致</b> —— 服务端可能乱序返回(带 text_index),
     * 实现必须重排回来,否则向量会和切片错配,而这种错配不会报错,只会让检索结果变差。
     */
    EmbeddingResult embedDocuments(List<String> texts);

    /**
     * 批量向量化的结果。
     *
     * {@code totalTokens} 是服务端回报的计费用量,用来校准本地 token 计数:
     * 本地用 cl100k_base,和实际模型的分词不完全一致,中文上偏差可能达到一倍以上。
     * 不校准的话,"每片不超过 N token"这个约束在真实调用时是失真的。
     */
    record EmbeddingResult(List<float[]> vectors, int totalTokens) {

        public EmbeddingResult {
            if (vectors == null) {
                throw new IllegalArgumentException("vectors 不能为空");
            }
            vectors = List.copyOf(vectors);
        }

        public int size() {
            return vectors.size();
        }
    }
}
