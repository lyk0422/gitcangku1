package com.example.starter.observation;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态及可选的冲突详情（冲突字段、当前版本）与稳定错误码，由全局异常处理器转为错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> conflictFields;
    private final Integer currentVersion;

    private ApiException(HttpStatus status, String message, String errorCode,
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
        return new ApiException(HttpStatus.BAD_REQUEST, message, null, null, null);
    }

    /**
     * 403：角色无权执行该操作（含对已删除墓碑观测的写操作）。
     */
    public static ApiException forbidden(String message, String errorCode) {
        return new ApiException(HttpStatus.FORBIDDEN, message, errorCode, null, null);
    }

    /**
     * 404：观测记录或基线版本不存在。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message, null, null, null);
    }

    /**
     * 404：资源不存在，携带稳定错误码以便区分失败原因。
     */
    public static ApiException notFound(String message, String errorCode) {
        return new ApiException(HttpStatus.NOT_FOUND, message, errorCode, null, null);
    }

    /**
     * 409：通用冲突（记录已存在、删除版本不匹配、requestId 异参复用）。
     */
    public static ApiException conflict(String message, Integer currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, message, null, null, currentVersion);
    }

    /**
     * 409：三方合并字段冲突，携带冲突字段与当前版本，不写入任何部分结果。
     */
    public static ApiException mergeConflict(List<String> conflictFields, int currentVersion) {
        return new ApiException(HttpStatus.CONFLICT,
                "merge conflict on fields: " + String.join(", ", conflictFields),
                null, List.copyOf(conflictFields), currentVersion);
    }

    /**
     * 410：记录已删除，拒绝新的修改和删除。
     */
    public static ApiException gone(String message) {
        return new ApiException(HttpStatus.GONE, message, null, null, null);
    }

    /**
     * 422：请求语义合法但业务条件不满足（规则未登记/已撤销、附件已清除无法再脱敏等），携带稳定错误码。
     */
    public static ApiException unprocessable(String message, String errorCode) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, errorCode, null, null);
    }
}
