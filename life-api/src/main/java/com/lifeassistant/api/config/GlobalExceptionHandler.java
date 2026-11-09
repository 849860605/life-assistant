package com.lifeassistant.api.config;

import cn.dev33.satoken.exception.NotLoginException;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把各类异常转换成统一的 ApiResponse 和对应的 HTTP 状态码。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：按错误码映射 HTTP 状态，文案用异常自带的 message。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        ErrorCode code = e.errorCode();
        HttpStatus status = switch (code) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case QUOTA_EXCEEDED, CREDIT_NOT_ENOUGH -> HttpStatus.PAYMENT_REQUIRED;
            case CAPTCHA_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case EXTERNAL_SERVICE -> HttpStatus.BAD_GATEWAY;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(code, e.getMessage()));
    }

    /** Sa-Token 未登录或 token 失效：统一返回 401。 */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(ErrorCode.UNAUTHORIZED, "请先登录"));
    }

    /** 请求体校验失败：只返回第一个字段错误，格式为「字段名 错误信息」。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst().orElse(ErrorCode.BAD_REQUEST.defaultMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.BAD_REQUEST, message));
    }

    /**
     * 参数非法（包括数字解析失败这类库里抛的）：返回 400 和通用文案，细节只记日志，不把内部信息透给前端。
     * 要给用户看具体原因的地方抛 BizException(BAD_REQUEST, 原因)。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("请求参数不对：{}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.BAD_REQUEST, "请求参数不对"));
    }

    /** 缺参数、参数类型不对、请求体不是合法 JSON：返回 400，不当成服务器错误。 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadParam(Exception e) {
        String message = e instanceof MissingServletRequestParameterException m ? "缺少参数 " + m.getParameterName()
                : e instanceof MethodArgumentTypeMismatchException t ? "参数 " + t.getName() + " 格式不对"
                : "请求内容格式不对";
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.BAD_REQUEST, message));
    }

    /** 不存在的地址（包括已关闭的接口文档）：返回 404，不当成服务器错误记日志。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(ErrorCode.NOT_FOUND, "地址不存在"));
    }

    /** 请求方法不对（比如对只收 POST 的接口发 GET）：返回 405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(ErrorCode.BAD_REQUEST, "不支持 " + e.getMethod() + " 请求"));
    }

    /** 上传的文件超过大小限制，或者没带文件：返回 400。 */
    @ExceptionHandler({org.springframework.web.multipart.MaxUploadSizeExceededException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> handleUpload(Exception e) {
        String message = e instanceof org.springframework.web.multipart.MaxUploadSizeExceededException
                ? "文件太大了，最大 15MB" : "没有收到文件";
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.BAD_REQUEST, message));
    }

    /** 用户中途关了页面或断网，回答写不出去了：不是程序出错，记一行就好，也没法再回响应。 */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleClientGone(Exception e) {
        log.debug("客户端已断开：{}", e.getMessage());
    }

    /** 兜底：记录错误日志，对外只返回通用的 500 文案，不暴露异常细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("未处理的异常", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.fail(ErrorCode.INTERNAL, ErrorCode.INTERNAL.defaultMessage()));
    }
}
