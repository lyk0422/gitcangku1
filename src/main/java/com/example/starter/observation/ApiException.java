package com.example.starter.observation;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态、可区分原因码及可选的冲突详情（冲突字段、当前版本），
 * 由全局异常处理器转为错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;
    private final List<String> conflictFields;
    private final Integer currentVersion;

    private ApiException(HttpStatus status, String reason, String message,
                         List<String> conflictFields, Integer currentVersion) {
        super(message);
        this.status = status;
        this.reason = reason;
        this.conflictFields = conflictFields;
        this.currentVersion = currentVersion;
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * 可区分原因码（如 EMBARGO_NOT_EXPIRED）；无细分原因时为 null，响应中省略。
     */
    public String reason() {
        return reason;
    }

    public List<String> conflictFields() {
        return conflictFields;
    }

    public Integer currentVersion() {
        return currentVersion;
    }

    /**
     * 400：请求参数不合法（如读数格式错误）。
     */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, null, message, null, null);
    }

    /**
     * 403：无权限或被禁运策略拒绝，携带原因码。
     */
    public static ApiException forbidden(String reason, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, reason, message, null, null);
    }

    /**
     * 404：观测记录或基线版本不存在。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, null, message, null, null);
    }

    /**
     * 404：携带原因码的资源不存在（如禁运未设置、尚未发布）。
     */
    public static ApiException notFound(String reason, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, reason, message, null, null);
    }

    /**
     * 409：通用冲突（记录已存在、删除版本不匹配、requestId 异参复用）。
     */
    public static ApiException conflict(String message, Integer currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, null, message, null, currentVersion);
    }

    /**
     * 409：携带原因码的冲突（如禁运未到期、重复设置禁运、尚未发布）。
     */
    public static ApiException conflict(String reason, String message, Integer currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, reason, message, null, currentVersion);
    }

    /**
     * 409：三方合并字段冲突，携带冲突字段与当前版本，不写入任何部分结果。
     */
    public static ApiException mergeConflict(List<String> conflictFields, int currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, null,
                "merge conflict on fields: " + String.join(", ", conflictFields),
                List.copyOf(conflictFields), currentVersion);
    }

    /**
     * 410：记录已删除，拒绝新的修改和删除。
     */
    public static ApiException gone(String message) {
        return new ApiException(HttpStatus.GONE, null, message, null, null);
    }

    /**
     * 422：业务状态不满足前置条件（禁运截止早于簇创建、披露范围为空、存在未解决冲突等），携带原因码。
     */
    public static ApiException unprocessable(String reason, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, reason, message, null, null);
    }
}