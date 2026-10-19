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

    @PostMapping
    public ApiResponse<DocumentView> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(DocumentView.from(documentService.upload(file)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentView> get(@PathVariable Long id) {
        return ApiResponse.ok(DocumentView.from(documentService.getById(id)));
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
