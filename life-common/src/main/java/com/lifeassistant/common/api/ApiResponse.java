package com.lifeassistant.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一接口返回结构。code 为 0 表示成功；data 为空时不输出该字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data) {

    /** 成功并带数据。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /** 成功且无数据。 */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    /** 失败：code 取自错误码，data 为空。 */
    public static <T> ApiResponse<T> fail(ErrorCodeView code, String message) {
        return new ApiResponse<>(code.code(), message, null);
    }

    /** 错误码的最小视图，避免 api 包反向依赖 error 包的具体枚举。 */
    public interface ErrorCodeView {
        /** 返回给前端的业务错误码。 */
        int code();
    }
}
