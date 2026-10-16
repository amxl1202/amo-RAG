package com.ragkb.document;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/** 上传/查询时对外的文档视图:不暴露存储路径细节。 */
@Data
@AllArgsConstructor
public class DocumentView {

    private Long id;
    private String originalName;
    private String sha256;
    private Long fileSize;
    private String status;
    private LocalDateTime createdAt;

    public static DocumentView from(DocumentEntity e) {
        return new DocumentView(e.getId(), e.getOriginalName(), e.getSha256(),
                e.getFileSize(), e.getStatus(), e.getCreatedAt());
    }
}
