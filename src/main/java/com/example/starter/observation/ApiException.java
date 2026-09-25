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

    private ApiException(HttpStatus status, String message, List<String> conflictFields, Integer currentVersion) {
        super(message);
        this.status = status;
        this.conflictFields = conflictFields;
        this.currentVersion = currentVersion;
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
     * 403：复核角色与标记提交角色相同，禁止复核。
     */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message, null, null);
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
     * 410：记录已删除，或标记绑定版本已失效，拒绝新的修改、删除与复核。
     */
    public static ApiException gone(String message) {
        return new ApiException(HttpStatus.GONE, message, null, null);
    }

    /**
     * 410：携带服务端当前版本号（如标记复核时观测已产生新版本）。
     */
    public static ApiException gone(String message, Integer currentVersion) {
        return new ApiException(HttpStatus.GONE, message, null, currentVersion);
    }
}
