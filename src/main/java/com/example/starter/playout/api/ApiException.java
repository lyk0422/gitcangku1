package com.example.starter.playout.api;

import com.example.starter.playout.api.Dtos.SponsorConflictDetail;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常，携带明确的 HTTP 状态码与错误码，由全局异常处理器转换为统一错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<SponsorConflictDetail> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<SponsorConflictDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 结构化冲突明细；无明细时为 null，不序列化。 */
    public List<SponsorConflictDetail> details() {
        return details;
    }

    /** 400：请求参数不合法。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /** 404：引用的资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409：版本、幂等或状态冲突。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 422：业务规则不满足。 */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /** 409：赞助排他冲突，携带频道、时段与冲突素材明细。 */
    public static ApiException sponsorConflict(List<SponsorConflictDetail> details) {
        SponsorConflictDetail first = details.get(0);
        return new ApiException(HttpStatus.CONFLICT, "SPONSOR_EXCLUSIVITY_CONFLICT",
                "命中赞助排他窗，共 " + details.size() + " 处冲突，首个: 频道 " + first.channelId()
                        + " 时段 [" + first.start() + ", " + first.end() + ") 素材 " + first.assetId(),
                details);
    }
}
