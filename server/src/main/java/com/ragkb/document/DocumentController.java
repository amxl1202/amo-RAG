package com.ragkb.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragkb.common.ApiResponse;
import com.ragkb.document.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentMapper documentMapper;
    private final IngestService ingestService;

    @PostMapping
    public ApiResponse<DocumentView> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(DocumentView.from(documentService.upload(file)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentView> get(@PathVariable Long id) {
        return ApiResponse.ok(DocumentView.from(documentService.getById(id)));
    }

    /**
     * 手动触发入库(解析 + 切片)。
     *
     * 上传后会自动异步触发一次;这个接口用于重跑 —— 改了切片参数、或上次失败想重试时用。
     * 它是同步的:调用方拿到的返回值就是"切出了多少片",而不是一个"已递交"的空响应。
     */
    @PostMapping("/{id}/ingest")
    public ApiResponse<IngestService.IngestResult> ingest(@PathVariable Long id) {
        return ApiResponse.ok(ingestService.ingest(id));
    }

    @GetMapping
    public ApiResponse<List<DocumentView>> list() {
        // FIXME(分层): 这里直接用了 mapper,绕过 service —— 与 upload/get 的写法不一致。
        //  一旦列表要加过滤条件(比如"只看解析成功的"),业务规则就散落在 controller 里。
        List<DocumentView> list = documentMapper.selectList(new LambdaQueryWrapper<DocumentEntity>()
                        .orderByDesc(DocumentEntity::getCreatedAt)).stream()
                .map(DocumentView::from)
                .toList();
        return ApiResponse.ok(list);
    }
}
