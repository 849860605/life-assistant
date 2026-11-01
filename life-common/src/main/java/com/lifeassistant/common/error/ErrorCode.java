package com.lifeassistant.common.error;

import com.lifeassistant.common.api.ApiResponse;

/**
 * 业务错误码，数值与 HTTP 状态码保持一致，并带默认提示文案。
 */
public enum ErrorCode implements ApiResponse.ErrorCodeView {
    BAD_REQUEST(400, "请求参数有误"),
    UNAUTHORIZED(401, "请先登录"),
    FORBIDDEN(403, "没有权限"),
    NOT_FOUND(404, "数据不存在"),
    CONFLICT(409, "数据已存在"),
    TOO_MANY_REQUESTS(429, "请求太频繁，请稍后再试"),
    EXTERNAL_SERVICE(502, "外部服务调用失败"),
    INTERNAL(500, "服务内部错误");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /** 业务错误码，同时也是对应的 HTTP 状态码。 */
    @Override
    public int code() {
        return code;
    }

    /** 未指定提示时返回给用户的默认文案。 */
    public String defaultMessage() {
        return defaultMessage;
    }
}
