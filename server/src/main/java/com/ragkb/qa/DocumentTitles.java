package com.ragkb.qa;

/**
 * 文档 ID → 文档标题。
 *
 * 检索结果只带 {@code documentId},而答案里的引用必须写清是哪份文档。
 * 这一层做成接口而不是让生成层直接查库:生成层要保持"脱离数据库也能测",
 * 标题来源(现在查 documents 表,将来可能来自缓存或对象存储元数据)不该写进它。
 */
@FunctionalInterface
public interface DocumentTitles {

    /** 查不到时返回占位串,不要返回 null —— null 会让提示词里出现 "null" 字样。 */
    String titleOf(long documentId);
}
