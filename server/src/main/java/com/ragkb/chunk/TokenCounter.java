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
