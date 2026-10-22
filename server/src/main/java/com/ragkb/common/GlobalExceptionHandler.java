package com.ragkb.common;

import com.ragkb.generation.ChatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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

    /**
     * 请求体读不出来(JSON 语法错、空 body、字段类型不对) → 400。
     *
     * 这一类必须显式接住:下面的 {@code Exception} 兜底会抢在 Spring 的默认解析器之前,
     * 把本该 400 的请求全部变成 500 —— 客户端会以为是自己写错了地址,而服务端日志里
     * 全是"服务内部错误"的堆栈,真正的 500 反而被淹没。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("malformed request body: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "请求体格式不正确"));
    }

    /** 路径/查询参数类型不对(例如 /api/documents/abc) → 400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("parameter type mismatch: {}={}", e.getName(), e.getValue());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "参数 " + e.getName() + " 的格式不正确"));
    }

    /** 缺少必填的 multipart part 或查询参数 → 400。 */
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(Exception e) {
        String name = e instanceof MissingServletRequestPartException part
                ? part.getRequestPartName()
                : ((MissingServletRequestParameterException) e).getParameterName();
        log.warn("missing request part/parameter: {}", name);
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "缺少必填项: " + name));
    }

    /** 方法不允许 → 405(而不是 500)。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(405, "该接口不支持 " + e.getMethod() + " 方法"));
    }

    /** Content-Type 不支持 → 415。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error(415, "请使用 application/json 提交"));
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
