package com.ragkb.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragkb.auth.AuthPrincipal;
import com.ragkb.common.ApiResponse;
import com.ragkb.document.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final VectorizeService vectorizeService;

    @PostMapping
    public ApiResponse<DocumentView> upload(@RequestParam("file") MultipartFile file,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(DocumentView.from(documentService.upload(file, principal.tenantId())));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentView> get(@PathVariable Long id,
                                          @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(DocumentView.from(documentService.getById(id, principal.tenantId())));
    }

    /**
     * 手动触发入库(解析 + 切片)。
     *
     * 上传后会自动异步触发一次;这个接口用于重跑 —— 改了切片参数、或上次失败想重试时用。
     * 它是同步的:调用方拿到的返回值就是"切出了多少片",而不是一个"已递交"的空响应。
     */
    @PostMapping("/{id}/ingest")
    public ApiResponse<IngestService.IngestResult> ingest(@PathVariable Long id,
                                                           @AuthenticationPrincipal AuthPrincipal principal) {
        // 先验归属再做重活:否则别的租户可以用你的 id 触发一次昂贵的重新解析
        documentService.getById(id, principal.tenantId());
        return ApiResponse.ok(ingestService.ingest(id));
    }

    /**
     * 向量化该文档的切片。
     *
     * 需要 DASHSCOPE_API_KEY:这一步会真的调用外部接口。同步返回用量,
     * 便于核对成本 —— 向量化是唯一按 token 计费的步骤,不该只回一个"已提交"。
     */
    @PostMapping("/{id}/vectorize")
    public ApiResponse<VectorizeService.VectorizeResult> vectorize(@PathVariable Long id,
                                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        documentService.getById(id, principal.tenantId());
        return ApiResponse.ok(vectorizeService.vectorize(id));
    }

    /**
     * 文档列表(分页)。
     *
     * <p>响应从"裸数组"改成 {@code {items,total,page,size}} —— 前端要知道总数才能画页码。
     * 默认 size=50,不传参数时行为与"返回前 50 条"一致,不会把整个库倒出来。
     */
    @GetMapping
    public ApiResponse<com.ragkb.common.PageResult<DocumentView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal AuthPrincipal principal) {
        com.ragkb.common.PageResult<DocumentEntity> result =
                documentService.list(principal.tenantId(), page, size, status);
        return ApiResponse.ok(new com.ragkb.common.PageResult<>(
                result.items().stream().map(DocumentView::from).toList(),
                result.total(), result.page(), result.size()));
    }
}
