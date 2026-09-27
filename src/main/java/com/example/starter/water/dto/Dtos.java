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

    /** 登记渠道日蒸发率命令；date 为 UTC 自然日（yyyy-MM-dd），rate 为小数 0～0.2、最多四位小数。 */
    public record RegisterEvaporationRateRequest(String commandKey, String date, String rate) {
    }

    /** 蒸发率版本视图，登记后不可变。 */
    public record EvaporationRateResponse(long id, String channelId, String date, int version, String rate,
                                          String createdUtc) {
    }

    /** 单笔核销送达水量命令。 */
    public record SettleRequest(String commandKey, String allocationKey, String date, String delivered) {
    }

    /** 批量核销的单项。 */
    public record SettleItemRequest(String allocationKey, String date, String delivered) {
    }

    /** 批量核销命令：任一 item 预校验失败则整批回滚。 */
    public record BatchSettleRequest(String commandKey, List<SettleItemRequest> items) {
    }

    /**
     * 核销记录视图（创建后不可变）。kind：SETTLEMENT / CORRECTION；
     * 更正记录的 delivered/loss/deduction 为原记录相反数；correctsId 非更正记录为 null；
     * upstreamRemaining 为本记录应用后上游持有额度快照。
     */
    public record SettlementResponse(long id, String allocationKey, long windowId, String channelId, String date,
                                     int rateVersion, String rate, String delivered, String loss, String deduction,
                                     String kind, Long correctsId, int batchNo, String upstreamRemaining,
                                     String createdUtc) {
    }

    /** 批量核销结果视图。 */
    public record BatchSettleResponse(List<SettlementResponse> settlements) {
    }

    /** 申请维度核销历史视图，按记录主键升序（含更正记录）。 */
    public record SettlementListResponse(String allocationKey, List<SettlementResponse> settlements) {
    }

    /**
     * 渠道日蒸发诊断视图（只读）。currentVersion/currentRate 为 null 表示当日未登记；
     * locked 为 true 表示当日已发生首次核销、禁止再改率；净额汇总把更正记录按负值计入。
     */
    public record EvaporationDiagnosticsResponse(String channelId, String date, Integer currentVersion,
                                                 String currentRate, boolean locked,
                                                 List<EvaporationRateResponse> versions,
                                                 String netDelivered, String netLoss, String netDeduction,
                                                 long settlementCount) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
