package com.example.starter.calibration.error;

import java.util.List;

/**
 * 业务异常，携带可区分的 HTTP 状态码与可选的明细项。
 *
 * <p>状态约定：400 参数非法，404 资源不存在，409 状态冲突，422 缺少有效证书。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final transient List<ItemReason> items;

    /**
     * @param status  HTTP 状态码
     * @param code    机器可识别的错误码
     * @param message 面向调用者的错误描述
     */
    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    /**
     * @param status  HTTP 状态码
     * @param code    机器可识别的错误码
     * @param message 面向调用者的错误描述
     * @param items   批量操作各项失败原因
     */
    public ApiException(int status, String code, String message, List<ItemReason> items) {
        super(message);
        this.status = status;
        this.code = code;
        this.items = items == null ? List.of() : List.copyOf(items);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ItemReason> items() {
        return items;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message);
    }

    /**
     * 批量放行中单个条目的失败原因。
     *
     * @param measurementId 测量 ID
     * @param reason        失败原因码
     * @param message       失败描述
     */
    public record ItemReason(long measurementId, String reason, String message) {
    }
}
