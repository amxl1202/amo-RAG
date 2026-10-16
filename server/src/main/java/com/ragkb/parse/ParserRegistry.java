package com.ragkb.parse;

import com.ragkb.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按扩展名分发到具体解析器。
 *
 * 这里刻意用「注册表 + 接口」而不是 if-else:新增格式 = 新增一个 @Component,
 * 分发代码不动;若写成 switch,每加一种格式都要回来改这个方法。
 */
@Component
public class ParserRegistry {

    private final Map<String, DocumentParser> byExtension = new HashMap<>();

    public ParserRegistry(List<DocumentParser> parsers) {
        for (DocumentParser parser : parsers) {
            for (String extension : parser.supportedExtensions()) {
                DocumentParser previous = byExtension.put(extension, parser);
                if (previous != null) {
                    throw new IllegalStateException("扩展名 ." + extension + " 被多个解析器认领: "
                            + previous.getClass().getSimpleName() + " / " + parser.getClass().getSimpleName());
                }
            }
        }
    }

    /** 已支持的扩展名(小写),供上传接口做白名单校验,避免两处各写一份常量。 */
    public Set<String> supportedExtensions() {
        return Set.copyOf(byExtension.keySet());
    }

    public DocumentParser require(String extension) {
        String key = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        DocumentParser parser = byExtension.get(key);
        if (parser == null) {
            throw ApiException.badRequest("暂不支持的文档格式: ." + key + ",当前支持 " + supportedExtensions());
        }
        return parser;
    }
}
