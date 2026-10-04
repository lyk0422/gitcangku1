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

    /** 创建窗口结算命令；requestId 为幂等键，同键同参重放首次结果，同键改参返回 409。 */
    public record SettlementRequest(String requestId) {
    }

    /** 窗口对账命令；expectedSettlementVersion 必须等于当前最新结算版本，否则 409。 */
    public record ReconcileRequest(String requestId, Long expectedSettlementVersion) {
    }

    /** 结算版本农户明细视图；volume 单位立方米（3 位小数），amount 单位元（精确到分）。 */
    public record SettlementLineResponse(String userId, String volume, String amount) {
    }

    /**
     * 结算版本视图。版本从 1 起；diffAmount 为与最近有效版本的差额（版本 1 等于应收总额），
     * 取消与转出产生负差额；费率与农户明细在创建时冻结，旧版本永不改写。
     */
    public record SettlementResponse(long id, long windowId, int version, String totalAmount,
                                     String diffAmount, String firstTierLimit, String firstTierRate,
                                     String secondTierRate, List<SettlementLineResponse> lines,
                                     String createdUtc) {
    }

    /** 窗口结算列表视图；currentVersion 与 currentNetReceivable 在无结算时为 null。 */
    public record SettlementListResponse(long windowId, Integer currentVersion,
                                         String currentNetReceivable,
                                         List<SettlementResponse> settlements) {
    }
    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
