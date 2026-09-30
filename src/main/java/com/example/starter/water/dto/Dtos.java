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
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，取消时归零；持有额度恰为零的已转出申请仍为 APPROVED）。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
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

    /** 窗口当前可用容量视图；approvedTotal 汇总 APPROVED 申请的当前持有额度。 */
    public record CapacityResponse(long windowId, String plannedVolume, String activeCurtailmentVolume,
                                   String availableTotal, String approvedTotal, String remaining) {
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

    /** 窗口历史明细：窗口本身 + 全部申请 + 全部限供记录。 */
    public record HistoryResponse(WindowResponse window, List<AllocationResponse> allocations,
                                  List<CurtailmentResponse> curtailments) {
    }

    // ------------------------------------------------------------------
    // 多来源水质混配
    // ------------------------------------------------------------------

    /** 创建水质来源命令；qualityIndex 省略或为 null 表示指标缺失（含该来源的混配申请将被 422 驳回）。 */
    public record BlendSourceRequest(String commandKey, String sourceKey, String name,
                                     String quotaVolume, String qualityIndex) {
    }

    /**
     * 水质来源视图。qualityIndex 为 null 表示指标缺失；reservedVolume 为全部未取消混配申请
     * 对该来源的预占体积之和；availableVolume = quotaVolume - reservedVolume。
     */
    public record BlendSourceResponse(String sourceKey, String name, String quotaVolume,
                                      String qualityIndex, String reservedVolume,
                                      String availableVolume, String createdUtc) {
    }

    /** 混配申请中的一个来源；volume 单位立方米，大于 0，最多 3 位小数。 */
    public record BlendSourceInput(String sourceKey, String volume) {
    }

    /**
     * 提交多来源水质混配申请命令。requestId 全局唯一且兼作幂等键；申请人由 X-Actor-Id 请求头提供。
     * qualityThreshold 为用途水质阈值（无量纲，最多 6 位小数），加权值等于阈值时仍可受理。
     */
    public record BlendSubmitRequest(String requestId, String userId, String purpose,
                                     String qualityThreshold, List<BlendSourceInput> sources) {
    }

    /**
     * 申请中的来源明细视图（提交时快照）。qualityIndex 为提交时来源指标；weightedValue 为
     * 体积×指标的加权贡献值；reserved 表示该体积当前是否仍预占来源配额（申请取消后为 false）。
     */
    public record BlendItemResponse(String sourceKey, String volume, String qualityIndex,
                                    String weightedValue, boolean reserved) {
    }

    /**
     * 混配申请视图。状态：RESERVED 已预占 / CANCELLED 已取消。totalVolume/weightedIndex 与各来源
     * 明细均为受理时快照，取消后保留原计算依据不重算；cancelledUtc 未取消时为 null。
     */
    public record BlendApplicationResponse(String requestId, String userId, String purpose,
                                           String qualityThreshold, String totalVolume,
                                           String weightedIndex, String requester, String status,
                                           String createdUtc, String cancelledUtc,
                                           List<BlendItemResponse> sources) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
