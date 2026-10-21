package com.ragkb.common;

import com.ragkb.generation.ChatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(ApiResponse.error(e.getStatus(), e.getMessage()));
    }

    /**
     * 参数校验失败。取第一条错误消息拼进响应 —— 前端要直接展示给用户,
     * 而默认的 "Validation failed for argument ..." 是给人看的还是给机器看的,分不清。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage() == null ? "参数不合法" : error.getDefaultMessage())
                .orElse("参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, message));
    }

    /**
     * 模型侧不可用。503 而不是 500:调用方据此知道"现在重试可能有用",
     * 而 500 会让它以为是自己请求写错了。
     */
    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ApiResponse<Void>> handleChat(ChatException e) {
        log.warn("model call failed: retryable={}, message={}", e.retryable(), e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(503, "模型服务暂时不可用,请稍后再试"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(413, "文件超过大小限制"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        // 完整堆栈只进日志,响应体不泄漏内部细节
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务内部错误"));
    }
}
