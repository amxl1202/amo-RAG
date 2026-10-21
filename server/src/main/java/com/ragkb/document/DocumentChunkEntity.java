package com.ragkb.document;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 切片元数据。
 *
 * 这里刻意<b>不</b>放向量列:向量维度由 embedding 模型决定,而模型的换代比切片策略快得多。
 * 把向量放进来意味着换模型就要改切片表的 DDL 并重刷全表;分开之后,
 * 换模型只是往向量表里按新维度重建一遍,切片本身可以原样复用。
 *
 * 写入方是 {@link ChunkStore}:入库流程把 {@link com.ragkb.chunk.ChunkDraft} 逐条落成这个实体。
 */
@Data
@TableName("document_chunks")
public class DocumentChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    /** 文档内顺序号,从 0 开始 */
    private Integer chunkIndex;

    /** 所属章节路径,如 "2. 技术栈 > 2.1 持久层";无标题正文为 null */
    private String sectionPath;

    private Integer headingLevel;

    private String content;

    /** cl100k_base 口径的 token 数,口径见 TokenCounter */
    private Integer tokenCount;

    /** 相对解析全文的字符区间 [charStart, charEnd) */
    private Integer charStart;

    private Integer charEnd;

    private Integer pageStart;

    private Integer pageEnd;

    private LocalDateTime createdAt;
}
