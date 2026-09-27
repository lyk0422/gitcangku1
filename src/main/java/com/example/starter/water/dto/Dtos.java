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

    /** 登记或修改渠道日蒸发率命令；rate 为 0~0.2 的十进制字符串，最多四位小数。 */
    public record UpsertEvaporationRateRequest(String commandKey, String rate) {
    }

    /**
     * 渠道日蒸发率视图。date 为 UTC 自然日（yyyy-MM-dd）；version 首次登记为 1，每次修改加 1；
     * locked 为 true 表示当日已存在核销，率不可再修改。
     */
    public record EvaporationRateResponse(String channelId, String date, String rate, int version,
                                          boolean locked, String createdUtc, String updatedUtc) {
    }

    /** 渠道蒸发率列表视图。 */
    public record EvaporationRateListResponse(String channelId, List<EvaporationRateResponse> rates) {
    }

    /** 单笔核销项：对 allocationKey 的持有额度按 date 当日渠道蒸发率核销 deliveredAmount 送达水量。 */
    public record SettlementItemRequest(String settlementKey, String allocationKey, String date,
                                        String deliveredAmount) {
    }

    /** 批量核销命令：整批预校验完整最终余额与逐笔舍入口径，任一失败整批回滚。 */
    public record SettlementBatchRequest(String commandKey, List<SettlementItemRequest> items) {
    }

    /**
     * 核销记录视图，创建后不可变。kind：SETTLEMENT 正常核销 / REVERSAL 反向更正（数量为负）。
     * 固化核销时的蒸发率与版本；deductedAmount = deliveredAmount + lossAmount（两位小数）；
     * remainingHeldAmount 为本记录生效后上游申请的剩余持有额度；reversalOfKey 仅反向更正记录非空。
     */
    public record SettlementResponse(String settlementKey, String kind, long windowId, String allocationKey,
                                     String channelId, String date, int rateVersion, String rate,
                                     String deliveredAmount, String lossAmount, String deductedAmount,
                                     String reversalOfKey, String remainingHeldAmount, String createdUtc) {
    }

    /** 批量核销结果视图，顺序与请求 items 一致。 */
    public record SettlementBatchResponse(List<SettlementResponse> settlements) {
    }

    /** 反向更正命令：对指定已结算核销生成等额反向记录并回补上游额度。 */
    public record ReverseSettlementRequest(String commandKey, String reversalKey) {
    }

    /** 申请核销历史视图（含反向更正），按发生顺序返回。 */
    public record SettlementListResponse(String allocationKey, List<SettlementResponse> settlements) {
    }

    /**
     * 渠道某日蒸发结算诊断视图（只读）。rate/version 未登记时为 null；
     * settled* 为正常核销合计，reversed* 为反向更正合计（负值），net* 为两者之和。
     */
    public record EvaporationDiagnosticsResponse(String channelId, String date, String rate, Integer version,
                                                 boolean locked, long settlementCount, long reversalCount,
                                                 String settledDelivered, String settledLoss,
                                                 String settledDeducted, String reversedDelivered,
                                                 String reversedLoss, String reversedDeducted,
                                                 String netDelivered, String netLoss, String netDeducted) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
