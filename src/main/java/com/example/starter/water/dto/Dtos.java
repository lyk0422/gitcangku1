package com.example.starter.water.dto;

import java.util.List;

/**
 * 灌区配水 API 请求/响应契约。水量在 API 层一律使用十进制字符串（单位立方米，最多 3 位小数），
 * 时间使用 ISO-8601 UTC 字符串。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 创建供水窗口命令。 */
    public record CreateWindowRequest(String commandKey, String windowKey, String channelId,
                                      String startUtc, String endUtc, String plannedVolume) {
    }

    /** 供水窗口视图；activeCurtailmentVolume 为 null 表示无生效限供。 */
    public record WindowResponse(long id, String windowKey, String channelId, String startUtc, String endUtc,
                                 String plannedVolume, String activeCurtailmentVolume, String availableTotal,
                                 String createdUtc) {
    }

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为尚未使用的当前持有额度（REQUESTED 为 0，普通批准时等于
     * 原水量，核销/转出时等额扣减，取消时归零；持有额度恰为零的已核销或已转出申请仍为 APPROVED）；
     * usedAmount 为累计已用水量，仅随核销单调增加，申请 CANCELLED 后仍保留并占用窗口容量，不提供冲销。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String usedAmount, String requester, String status,
                                     String createdUtc, String updatedUtc) {
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

    /**
     * 窗口当前可用容量视图。
     * availableTotal 为计划水量（有生效限供时为限供水量，即有效限供后的总量上限）；
     * usedTotal 为全部申请（含 CANCELLED）累计已用水量；approvedTotal 为 APPROVED 申请当前持有（未用）额度之和；
     * occupiedTotal = usedTotal + approvedTotal 为窗口已占用量（核销不改变占用总量，取消不释放已用部分）；
     * remaining = availableTotal - occupiedTotal 为可用余量，任何提交后不得为负。
     */
    public record CapacityResponse(long windowId, String plannedVolume, String activeCurtailmentVolume,
                                   String availableTotal, String usedTotal, String approvedTotal,
                                   String occupiedTotal, String remaining) {
    }

    /** 同窗口额度转让命令；操作人（源申请人）由 X-Actor-Id 请求头提供。 */
    public record TransferRequest(String commandKey, String transferKey, String sourceAllocationKey,
                                  String targetAllocationKey) {
    }

    /** 转让流水视图，创建后不可变，不提供撤销。 */
    public record TransferResponse(String transferKey, long windowId, String sourceAllocationKey,
                                   String targetAllocationKey, String amount, String actor, String createdUtc) {
    }

    /** 窗口转让流水列表视图。 */
    public record TransferListResponse(long windowId, List<TransferResponse> transfers) {
    }

    /** 实际用水核销命令；核销人（原申请人）由 X-Actor-Id 请求头提供。 */
    public record ConsumeUsageRequest(String commandKey, String usageKey, String allocationKey, String amount) {
    }

    /** 核销流水视图，创建后不可变，不提供冲销。 */
    public record UsageResponse(String usageKey, String allocationKey, long windowId, String amount,
                                String actor, String createdUtc) {
    }

    /** 窗口核销流水列表视图。 */
    public record UsageListResponse(long windowId, List<UsageResponse> usages) {
    }

    /**
     * 核销结果：不可变流水加核销后口径快照。
     * usedAmount 为该申请累计已用、heldAmount 为尚未使用的持有额度；
     * occupiedTotal 为窗口已占用量，availableTotal 为有效总量上限，remaining 为可用余量。
     */
    public record UsageConsumeResponse(UsageResponse usage, String usedAmount, String heldAmount,
                                       String occupiedTotal, String availableTotal, String remaining) {
    }

    /** 窗口历史明细：窗口本身 + 全部申请 + 全部限供记录 + 全部核销流水。 */
    public record HistoryResponse(WindowResponse window, List<AllocationResponse> allocations,
                                  List<CurtailmentResponse> curtailments, List<UsageResponse> usages) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
