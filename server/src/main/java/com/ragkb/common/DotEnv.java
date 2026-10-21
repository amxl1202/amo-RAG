package com.ragkb.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读 {@code .env} 的最小实现:只认 {@code KEY=VALUE} 与 {@code #} 注释。
 *
 * <h2>为什么命令行工具需要它</h2>
 * Spring 的 {@code spring.config.import} 只在容器里生效,而评估跑批、提示词预览这些工具
 * 不启动 Spring —— 它们读的是进程环境。不在工具里读一遍 .env,使用者就得先 export
 * 一堆变量才能跑,这层摩擦会让人干脆不跑。
 *
 * <h2>为什么不做完整解析</h2>
 * 不做变量插值、不做多行值、不做 export 前缀:这些在 .env 里出现就是配置写错了,
 * 静默支持反而会让人以为写对了。真正复杂的配置该走 yml,不该走 .env。
 */
public final class DotEnv {

    private DotEnv() {
    }

    /** 读不到文件时返回空表 —— 工具应当退回进程环境变量,而不是直接崩掉。 */
    public static Map<String, String> load(Path path) {
        Map<String, String> values = new HashMap<>();
        if (path == null || !Files.isRegularFile(path)) {
            return values;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return values;
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                // 引号内的内容原样保留:密码里出现 " #" 是合法的
                value = value.substring(1, value.length() - 1);
            } else {
                value = stripInlineComment(value);
            }
            values.put(key, value);
        }
        return values;
    }

    /**
     * 截掉行尾注释。
     *
     * 只认"空白 + #":.env 里常见的写法是 {@code KEY=value # 备注}。
     * 不认紧贴的 {@code value#x} —— 那更像值的一部分(锚点、片段标识),
     * 且真实密码里出现 {@code #} 的比出现 {@code " #"} 的多得多。
     * 需要保留 {@code " #"} 时把整个值用双引号包起来。
     */
    private static String stripInlineComment(String value) {
        int marker = value.indexOf(" #");
        return marker < 0 ? value : value.substring(0, marker).trim();
    }

    /**
     * 取配置值:进程环境优先,其次 .env,最后默认值。
     *
     * 这个优先级让 CI 里 export 的变量能覆盖本地文件 —— 排查线上问题时不必改文件。
     */
    public static String get(Map<String, String> dotEnv, String name, String fallback) {
        String fromProcess = System.getenv(name);
        if (fromProcess != null && !fromProcess.isBlank()) {
            return fromProcess;
        }
        String fromFile = dotEnv.get(name);
        return fromFile == null || fromFile.isBlank() ? fallback : fromFile;
    }
}
