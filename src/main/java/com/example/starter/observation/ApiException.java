package com.example.starter.observation;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态、机器可读错误码及可选的冲突详情（冲突字段、当前版本），
 * 由全局异常处理器转为错误响应。errorCode 用于让调用方区分同状态码下的不同失败原因。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> conflictFields;
    private final Integer currentVersion;

    private ApiException(HttpStatus status, String errorCode, String message,
                         List<String> conflictFields, Integer currentVersion) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.conflictFields = conflictFields;
        this.currentVersion = currentVersion;
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * 机器可读错误码（如 RULE_REVOKED）；历史异常路径可为 null。
     */
    public String errorCode() {
        return errorCode;
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
     * 400：请求参数不合法，携带机器可读错误码。
     */
    public static ApiException badRequest(String errorCode, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, errorCode, message, null, null);
    }

    /**
     * 403：角色未授权执行该操作。
     */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", message, null, null);
    }

    /**
     * 404：观测记录、附件、规则或版本不存在。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, null, message, null, null);
    }

    /**
     * 404：资源不存在，携带机器可读错误码（如 OBSERVATION_TOMBSTONE）。
     */
    public static ApiException notFound(String errorCode, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, errorCode, message, null, null);
    }

    /**
     * 409：通用冲突（记录已存在、删除版本不匹配、requestId 异参复用）。
     */
    public static ApiException conflict(String message, Integer currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, null, message, null, currentVersion);
    }

    /**
     * 409：通用冲突，携带机器可读错误码。
     */
    public static ApiException conflict(String errorCode, String message, Integer currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, errorCode, message, null, currentVersion);
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
     * 422：业务规则不满足（如使用已撤销规则、对已清除内容再脱敏），携带机器可读错误码。
     */
    public static ApiException unprocessable(String errorCode, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message, null, null);
    }
}
