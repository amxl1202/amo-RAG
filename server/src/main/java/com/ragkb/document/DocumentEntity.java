package com.ragkb.document;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档元数据。status 表示入库后所处的流水线阶段,
 * 后续阶段(解析→切片→向量化)推进状态机,失败停在 FAILED 并记录原因。
 */
@Data
@TableName("documents")
public class DocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户看到的原始文件名 */
    private String originalName;

    /** 内容摘要(hex),全库唯一,作为去重与内容寻址存储的键 */
    private String sha256;

    private Long fileSize;

    private String contentType;

    /** 相对存储路径(如 2026/10/ab12....pdf),不暴露绝对路径 */
    private String storagePath;

    /** UPLOADED / PARSING / CHUNKED / EMBEDDING / READY / FAILED */
    private String status;

    private String failReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
