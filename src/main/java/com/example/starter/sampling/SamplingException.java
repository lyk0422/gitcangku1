package com.example.starter.sampling;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 采样许可业务异常：携带 HTTP 状态、可区分原因码及待落库的拒绝记录草稿。
 * 422 预校验失败会携带拒绝记录草稿，由服务层在业务事务回滚后以独立事务追加落库。
 */
public class SamplingException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;
    private final List<RejectionDraft> rejections;

    private SamplingException(HttpStatus status, String reason, String message, List<RejectionDraft> rejections) {
        super(message);
        this.status = status;
        this.reason = reason;
        this.rejections = rejections;
    }

    public HttpStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    /**
     * 待落库的拒绝记录草稿；仅 422 预校验失败非空。
     */
    public List<RejectionDraft> rejections() {
        return rejections;
    }

    /**
     * 400：请求参数不合法（如有效期起点不早于终点）。
     */
    public static SamplingException badRequest(String message) {
        return new SamplingException(HttpStatus.BAD_REQUEST, "VALIDATION", message, List.of());
    }

    /**
     * 404：许可、观测或冲突簇不存在。
     */
    public static SamplingException notFound(String message) {
        return new SamplingException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of());
    }

    /**
     * 409：许可重叠、许可状态不允许、批次标识异参复用、簇非待裁决等冲突。
     */
    public static SamplingException conflict(String reason, String message) {
        return new SamplingException(HttpStatus.CONFLICT, reason, message, List.of());
    }

    /**
     * 422：提交/批量预校验失败（无许可、命中不唯一、超限、版本不一致、坐标越界），携带拒绝记录草稿。
     */
    public static SamplingException unprocessable(String reason, String message, List<RejectionDraft> rejections) {
        return new SamplingException(HttpStatus.UNPROCESSABLE_ENTITY, reason, message, List.copyOf(rejections));
    }
}
