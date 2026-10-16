package com.ragkb.parse;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * 文档解析器:把字节流变成按阅读顺序排列的结构块。
 *
 * 边界约定:解析器只做"格式 → 结构块",不碰切片、不碰数据库、不知道文档 id。
 * 这样换解析库(PDFBox → 别的)不会波及切片与检索;反过来切片策略调整也不需要动解析。
 */
public interface DocumentParser {

    /** 本解析器认领的扩展名(小写、不含点)。注册表用它做分发。 */
    Set<String> supportedExtensions();

    /**
     * 解析文档。实现必须是纯函数:相同字节流产生相同结果,不读磁盘以外的外部状态。
     *
     * @param content 文档字节流,由调用方负责关闭
     * @return 结构块序列,可为空列表;不得返回 null
     */
    List<RawBlock> parse(InputStream content) throws IOException;
}
