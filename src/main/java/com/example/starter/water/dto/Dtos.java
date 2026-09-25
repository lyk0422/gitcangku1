package com.example.starter.water.dto;

import java.util.List;

/**
 * 灌区配水 API 请求/响应契约。水量在 API 层一律使用十进制字符串（单位立方米，最多 3 位小数），
 * 时间使用 ISO-8601 UTC 字符串。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 创建供水窗口命令；quarter 为所属季度（1~4），可空，空表示不参与季度结转。 */
    public record CreateWindowRequest(String commandKey, String windowKey, String channelId,
                                      String startUtc, String endUtc, String plannedVolume, Integer quarter) {
    }

    /** 供水窗口视图；activeCurtailmentVolume 为 null 表示无生效限供，quarter 为 null 表示未标记季度。 */
    public record WindowResponse(long id, String windowKey, String channelId, String startUtc, String endUtc,
                                 String plannedVolume, String activeCurtailmentVolume, String availableTotal,
                                 Integer quarter, String createdUtc) {
    }

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount) {
    }

    /** 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。 */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String requester, String status, String createdUtc, String updatedUtc) {
    }

    /** 仅含幂等键的命令（批准/取消申请、取消限供）。 */
    public record CommandRequest(String commandKey) {
    }

    /** 创建限供命令。 */
    public record CurtailmentRequest(String commandKey, String volume) {
    }

    /** 限供视图。状态：ACTIVE / CANCELLED；cancelledUtc 未取消时为 null。 */
    public record CurtailmentResponse(long id, long windowId, String volume, String status,
                                      String createdUtc, String cancelledUtc) {
    }

    /** 窗口当前可用容量视图。 */
    public record CapacityResponse(long windowId, String plannedVolume, String activeCurtailmentVolume,
                                   String availableTotal, String approvedTotal, String remaining) {
    }

    /** 窗口历史明细：窗口本身 + 全部申请 + 全部限供记录。 */
    public record HistoryResponse(WindowResponse window, List<AllocationResponse> allocations,
                                  List<CurtailmentResponse> curtailments) {
    }

    /** 季度结转命令：把源窗口本用水户 APPROVED 申请的未用余量迁移到同季度目标窗口。 */
    public record CarryoverRequest(String commandKey, String carryoverKey, Long sourceWindowId,
                                   Long targetWindowId, String userId, String amount) {
    }

    /** 结转流水视图，不可变。 */
    public record CarryoverResponse(String carryoverKey, long sourceWindowId, long targetWindowId,
                                    String userId, String amount, String targetAllocationKey,
                                    String createdUtc) {
    }

    /** 单条申请的跨窗口余量视图；remaining = amount - consumedVolume - carriedOutVolume。 */
    public record CarryoverBalanceEntry(String allocationKey, long windowId, Integer windowQuarter,
                                        String status, String amount, String consumedVolume,
                                        String carriedOutVolume, String remaining) {
    }

    /** 按用水户的跨窗口余量查询响应。 */
    public record CarryoverBalanceResponse(String userId, List<CarryoverBalanceEntry> allocations) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
