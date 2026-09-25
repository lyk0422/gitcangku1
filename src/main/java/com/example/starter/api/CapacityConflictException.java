package com.example.starter.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 容量不可满足错误（HTTP 422）：紧急航线移除全部可抢占 NORMAL 后容量仍不足，
 * 或普通航线无剩余容量。响应体列出不可抢占航线及原因。
 */
public class CapacityConflictException extends RuntimeException {

    /** 不可抢占航线明细，会原样写入错误响应。 */
    private final List<BlockingRoute> blockingRoutes;

    public CapacityConflictException(String message, List<BlockingRoute> blockingRoutes) {
        super(message);
        this.blockingRoutes = List.copyOf(blockingRoutes);
    }

    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    public String code() {
        return "CAPACITY_NOT_AVAILABLE";
    }

    public List<BlockingRoute> blockingRoutes() {
        return blockingRoutes;
    }

    /**
     * 不可抢占航线明细。
     *
     * @param routeId     航线标识
     * @param clearanceId 批件标识
     * @param priority    占用方优先级 NORMAL / EMERGENCY
     * @param status      占用方批件状态 APPROVED / DEPARTED
     * @param reason      不可抢占原因：DEPARTED 已起飞；EMERGENCY 另一条紧急航线
     */
    public record BlockingRoute(String routeId, String clearanceId, String priority,
                                String status, String reason) {
    }
}
