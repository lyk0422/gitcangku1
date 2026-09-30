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

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }

    /** 注册水质来源命令；qualityIndex 为该来源当前水质指标（无量纲），totalQuota 为可用配额（立方米）。 */
    public record RegisterSourceRequest(String commandKey, String sourceKey, String name,
                                        String qualityIndex, String totalQuota) {
    }

    /** 水质来源视图；reservedVolume 为未取消申请的预占体积合计，remaining = totalQuota - reservedVolume。 */
    public record BlendSourceResponse(String sourceKey, String name, String qualityIndex, String totalQuota,
                                      String reservedVolume, String remaining, String createdUtc,
                                      String updatedUtc) {
    }

    /** 混配申请中的单个来源：体积（立方米，大于 0）与该来源水质指标（无量纲）。 */
    public record BlendSourceInput(String sourceKey, String volume, String qualityIndex) {
    }

    /**
     * 提交多来源水质混配申请命令。同一 requestId 重放首次配额与计算结果；
     * 受理时按 Σ(来源体积×指标)/Σ来源体积 计算加权指标，与 threshold 比较（相等可受理）。
     */
    public record SubmitBlendRequest(String requestId, String userId, String purpose, String threshold,
                                     List<BlendSourceInput> sources) {
    }

    /** 申请来源明细视图；qualityIndex/weightedIndex 为受理当时快照，取消后不重算。 */
    public record BlendSourceItemResponse(String sourceKey, String volume, String qualityIndex,
                                          String weightedIndex, String reservedStatus) {
    }

    /** 混配申请视图。状态：RESERVED 已预占 / CANCELLED 已取消；取消后保留原体积、指标与加权值依据。 */
    public record BlendApplicationResponse(String requestId, String userId, String purpose, String threshold,
                                           String totalVolume, String weightedIndex, String requester,
                                           String status, String createdUtc, String updatedUtc,
                                           String cancelledUtc, List<BlendSourceItemResponse> sources) {
    }
}
