package com.ragkb.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

/**
 * token 计数。
 *
 * 为什么不能用 String.length():BPE 分词器不按字符切。
 * 同一段文本,"你好世界"在 BPE 里可能是 2~4 个 token,而 "hello world" 是 2 个;
 * 一段 1000 字符的中文与 1000 字符的英文,送进模型后的 token 数能差一倍以上。
 * 用字符数估算,要么撑爆模型的上下文上限,要么白白浪费一半窗口。
 *
 * 关于本实现用 cl100k_base 的诚实说明:
 * text-embedding-v3 与 qwen-plus 用的是通义自己的分词器,不是 cl100k。
 * cl100k 对中文的切分比 Qwen 更碎(1 个汉字常占 1~2 个 token),所以它给出的是**偏大**的估计。
 * 偏大对"别超上限"是安全的,代价是同样 token 预算下中文切片会比预期短一点。
 *
 * 想把偏差消掉,唯一可靠的办法是用真实接口校准(需要 DashScope API Key,接入向量化时一并做):
 * 1. 把一段已知文本发给 embedding 接口,读返回的 usage.total_tokens;
 * 2. 用 count(text) 得到本机估计值;
 * 3. 两者相除得到这个模型的实际倍率,把它记进配置,再修正切片阈值。
 * 在此之前,下面所有阈值都以 cl100k 为单位表述,不要混用别的口径。
 *
 * <b>校准结果(2026-10-21 实测)</b>:本地计数<b>偏高</b>。
 * 用 5 份文档共 42 个切片整批向量化,接口回报 {@code usage.total_tokens} 合计 9135,
 * 本地 cl100k 合计 11770,倍率 <b>0.776</b>(实际 ≈ 本地 × 0.776)。
 * 也就是说"每片不超过 400"在真实口径下约等于 310,是<b>保守</b>的 —— 不会撞上模型上限,
 * 但"一片 300 token"这句话的含义要比字面小四分之一。
 *
 * 三点使用注意:
 * <ol>
 *   <li>这个倍率来自 embedding 接口的批量计费,单条调用与批量调用可能有细微差异,
 *       不要拿它当精确换算,当"量级修正"用;</li>
 *   <li>换模型(或换供应商)必须重测,倍率是模型相关的,不是语言相关的;</li>
 *   <li>如果将来要让"每片 300"指的是真实 token,应当把 target 反算成
 *       {@code 300 / 0.776 ≈ 386} 的本地口径 —— 但改参数要重跑评估,不能只改数字。</li>
 * </ol>
 * 重测方法:配好 Key 后对整批切片调 {@code POST /api/documents/{id}/vectorize},
 * 把它回报的 totalTokens 累加,与 {@code SELECT sum(token_count) FROM document_chunks} 相除。
 */
@Component
public class TokenCounter {

    private static final EncodingType ENCODING_TYPE = EncodingType.CL100K_BASE;

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry().getEncoding(ENCODING_TYPE);

    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /** 编码器名,写日志/落库时带上,免得日后换了编码而历史数据的口径说不清。 */
    public String encodingName() {
        return ENCODING_TYPE.getName();
    }
}
