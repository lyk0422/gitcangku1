package com.example.starter.observation;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态及可选的冲突详情（冲突字段、当前版本）与校验详情（实际值、要求值），
 * 由全局异常处理器转为错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> conflictFields;
    private final Integer currentVersion;
    private final String actualValue;
    private final String requiredValue;

    private ApiException(HttpStatus status, String message, List<String> conflictFields, Integer currentVersion) {
        this(status, message, conflictFields, currentVersion, null, null);
    }

    private ApiException(HttpStatus status, String message, List<String> conflictFields, Integer currentVersion,
                         String actualValue, String requiredValue) {
        super(message);
        this.status = status;
        this.conflictFields = conflictFields;
        this.currentVersion = currentVersion;
        this.actualValue = actualValue;
        this.requiredValue = requiredValue;
    }

    public HttpStatus status() {
        return status;
    }

    public List<String> conflictFields() {
        return conflictFields;
    }

    public Integer currentVersion() {
        return currentVersion;
    }

    public String actualValue() {
        return actualValue;
    }

    public String requiredValue() {
        return requiredValue;
    }

    /**
     * 400：请求参数不合法（如读数格式错误）。
     */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, null, null);
    }

    /**
     * 404：观测记录或基线版本不存在。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message, null, null);
    }

    /**
     * 409：通用冲突（记录已存在、删除版本不匹配、requestId 异参复用）。
     */
    public static ApiException conflict(String message, Integer currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, message, null, currentVersion);
    }

    /**
     * 409：三方合并字段冲突，携带冲突字段与当前版本，不写入任何部分结果。
     */
    public static ApiException mergeConflict(List<String> conflictFields, int currentVersion) {
        return new ApiException(HttpStatus.CONFLICT,
                "merge conflict on fields: " + String.join(", ", conflictFields),
                List.copyOf(conflictFields), currentVersion);
    }

    /**
     * 410：记录已删除，拒绝新的修改和删除。
     */
    public static ApiException gone(String message) {
        return new ApiException(HttpStatus.GONE, message, null, null);
    }

    /**
     * 422：经纬度范围、围栏边界或生效区间等语义校验失败，携带实际值与要求值（或差额说明）。
     */
    public static ApiException unprocessable(String message, String actualValue, String requiredValue) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, null, null,
                actualValue, requiredValue);
    }
}
