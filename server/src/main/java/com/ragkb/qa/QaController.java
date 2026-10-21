package com.ragkb.qa;

import com.ragkb.common.ApiException;
import com.ragkb.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问答接口。
 *
 * 这一层只做协议转换:请求体 → service → 响应体。编排逻辑全在 {@link QaService},
 * 因为跑批工具也要走同一套编排 —— 如果编排写在 controller 里,评估就会漏掉真实链路。
 */
@RestController
@RequestMapping("/api/qa")
@RequiredArgsConstructor
public class QaController {

    private final QaService qaService;

    /**
     * 提问。
     *
     * <p>FAILED 单独转成 503:它意味着检索两路都挂了或模型调用失败,是"现在别重试太多次"
     * 的服务端问题;而 REFUSED 是正常业务结果,仍然 200。
     */
    @PostMapping
    public ApiResponse<QaResponse> ask(@Valid @RequestBody QaRequest request) {
        QaAnswer answer = qaService.ask(request.question());
        if (answer.status() == QaAnswer.Status.FAILED) {
            throw new ApiException(503, "问答服务暂时不可用,请稍后再试");
        }
        return ApiResponse.ok(QaResponse.from(answer));
    }
}
