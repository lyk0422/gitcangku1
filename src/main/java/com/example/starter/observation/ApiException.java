package com.example.starter.observation;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态、可选的冲突详情（冲突字段、当前版本）与可选的逐簇错误明细，
 * 由全局异常处理器转为错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> conflictFields;
    private final Integer currentVersion;
    private final List<ErrorDetail> details;

    private ApiException(HttpStatus status, String message, List<String> conflictFields,
                         Integer currentVersion, List<ErrorDetail> details) {
        super(message);
        this.status = status;
        this.conflictFields = conflictFields;
        this.currentVersion = currentVersion;
        this.details = details;
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

    public List<ErrorDetail> details() {
        return details;
    }

    /**
     * 400：请求参数不合法（如读数格式错误）。
     */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, null, null, null);
    }

    /**
     * 404：观测记录或基线版本不存在。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message, null, null, null);
    }

    /**
     * 409：通用冲突（记录已存在、删除版本不匹配、requestId 异参复用）。
     */
    public static ApiException conflict(String message, Integer currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, message, null, currentVersion, null);
    }

    /**
     * 409：通用冲突并携带逐簇错误明细（如禁运重复设置）。
     */
    public static ApiException conflictWithDetails(String message, List<ErrorDetail> details) {
        return new ApiException(HttpStatus.CONFLICT, message, null, null, List.copyOf(details));
    }

    /**
     * 409：三方合并字段冲突，携带冲突字段与当前版本，不写入任何部分结果。
     */
    public static ApiException mergeConflict(List<String> conflictFields, int currentVersion) {
        return new ApiException(HttpStatus.CONFLICT,
                "merge conflict on fields: " + String.join(", ", conflictFields),
                List.copyOf(conflictFields), currentVersion, null);
    }

    /**
     * 410：记录已删除，拒绝新的修改和删除。
     */
    public static ApiException gone(String message) {
        return new ApiException(HttpStatus.GONE, message, null, null, null);
    }

    /**
     * 422：语义校验失败（禁运截止早于簇创建、披露范围为空、存在未解决冲突、禁运未到期等），
     * 携带逐簇错误明细以区分原因；不写入任何部分结果。
     */
    public static ApiException unprocessable(String message, List<ErrorDetail> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, null, null, List.copyOf(details));
    }
}