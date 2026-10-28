package com.lifeassistant.common.error;

/**
 * 业务异常：携带 ErrorCode，由 GlobalExceptionHandler 转成对应 HTTP 状态和统一返回结构。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 使用错误码的默认文案。 */
    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 本异常对应的错误码。 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** NOT_FOUND，文案为「{what}不存在」。 */
    public static BizException notFound(String what) {
        return new BizException(ErrorCode.NOT_FOUND, what + "不存在");
    }

    /** FORBIDDEN，使用默认文案。 */
    public static BizException forbidden() {
        return new BizException(ErrorCode.FORBIDDEN);
    }
}
