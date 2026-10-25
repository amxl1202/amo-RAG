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

    @GetMapping
    public ApiResponse<List<DocumentView>> list(@AuthenticationPrincipal AuthPrincipal principal) {
        // 原来这里直接用了 mapper(留了 FIXME)。加租户过滤正好是把它收回 service 的时机:
        // "哪些文档可见"是业务规则,不该散落在 controller 里
        return ApiResponse.ok(documentService.list(principal.tenantId()).stream()
                .map(DocumentView::from)
                .toList());
    }
}
