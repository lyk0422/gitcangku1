package com.example.starter.observation;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态及可选的冲突详情（冲突字段、当前版本），由全局异常处理器转为错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> conflictFields;
    private final Integer currentVersion;
    private final Integer actual;
    private final Integer required;

    private ApiException(HttpStatus status, String message, List<String> conflictFields, Integer currentVersion) {
        this(status, message, conflictFields, currentVersion, null, null);
    }

    private ApiException(HttpStatus status, String message, List<String> conflictFields,
                         Integer currentVersion, Integer actual, Integer required) {
        super(message);
        this.status = status;
        this.conflictFields = conflictFields;
        this.currentVersion = currentVersion;
        this.actual = actual;
        this.required = required;
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

    /**
     * 数量类校验失败时的实际值（如超长摘要的实际长度）；无数量语境时为 null。
     */
    public Integer actual() {
        return actual;
    }

    /**
     * 数量类校验失败时的要求值（如允许的最大长度）；无数量语境时为 null。
     */
    public Integer required() {
        return required;
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
     * 403：角色不具备执行该操作的权限（如非脱敏角色提交脱敏、非证据角色读取原始摘要）。
     */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message, null, null);
    }

    /**
     * 422：业务规则不满足（如规则版本未登记/已撤销、附件状态不允许）。
     */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, null, null);
    }

    /**
     * 422：数量类约束不满足，携带实际值与要求值，便于调用方直接比较。
     */
    public static ApiException unprocessable(String message, int actual, int required) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, null, null, actual, required);
    }
}
