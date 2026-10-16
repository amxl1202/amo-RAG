package com.ragkb.common;

import lombok.Getter;

/** 业务异常:携带 HTTP 状态码,由 GlobalExceptionHandler 统一转成响应体。 */
@Getter
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }
}
