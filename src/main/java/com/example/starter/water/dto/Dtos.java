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
     * 转出时等额扣减，核销时按 上游扣减=送达+损耗 扣减，取消/更正时相应变化；持有额度恰为零的已转出申请仍为 APPROVED）。
     * settledDelivered/settledLoss 为仍有效核销的累计送达水量与累计蒸发损耗，反向更正时等额冲回。
     * 历史字段顺序保持稳定，新字段只追加在末尾。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
                                     String createdUtc, String updatedUtc,
                                     String settledDelivered, String settledLoss) {
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

    /** 渠道日蒸发率登记/修改命令；日期为 UTC 自然日（yyyy-MM-dd）。 */
    public record EvaporationRateRequest(String commandKey, String channelId, String rateDate, String rate) {
    }

    /**
     * 日蒸发率版本视图。version 从 1 递增，current=true 为当日当前版本；
     * 核销凭证固化的是具体版本，后续追加新版本不改写旧证据。
     */
    public record EvaporationRateResponse(long id, String channelId, String rateDate, String rate,
                                          int version, boolean current, String createdUtc) {
    }

    /** 渠道蒸发率版本历史（含已被取代的版本），按日期、版本升序。 */
    public record EvaporationRateHistoryResponse(String channelId, List<EvaporationRateResponse> rates) {
    }

    /** 单笔核销项；送达日为 UTC 自然日，送达水量单位立方米（最多 3 位小数）。 */
    public record SettlementItemRequest(String settlementKey, String allocationKey,
                                        String deliveryDate, String deliveredVolume) {
    }

    /** 单笔核销命令（等价于一批一项）。 */
    public record SettleRequest(String commandKey, String batchKey, String allocationKey,
                                String deliveryDate, String deliveredVolume) {
    }

    /** 批量核销命令；按完整最终余额与逐笔舍入口径预校验，任一失败整批回滚。 */
    public record BatchSettleRequest(String commandKey, String batchKey, List<SettlementItemRequest> items) {
    }

    /**
     * 核销凭证视图，创建后不可变：固化蒸发率版本（rateVersion/rateValue/rateDate）、原始送达量、
     * 银行家舍入后的损耗与上游扣减（upstreamDeduction = delivered + loss）。
     * status：SETTLED 已结算 / REVERSED 已被反向更正。
     */
    public record SettlementResponse(String settlementKey, String batchKey, long windowId, String channelId,
                                     String allocationKey, String deliveryDate, int rateVersion, String rate,
                                     String deliveredVolume, String loss, String upstreamDeduction,
                                     String status, String createdUtc) {
    }

    /** 批量核销结果；settlements 与请求项顺序一致。 */
    public record BatchSettleResponse(String batchKey, long windowId, int itemCount,
                                      List<SettlementResponse> settlements) {
    }

    /** 反向更正命令；只能更正 SETTLED 凭证，冲回后原凭证置 REVERSED。 */
    public record CorrectionRequest(String commandKey, String correctionKey, String reason) {
    }

    /** 反向更正凭证视图，创建后不可变。 */
    public record CorrectionResponse(String correctionKey, String settlementKey, long windowId,
                                     String deliveredRefund, String lossRefund, String deductionRefund,
                                     String reason, String createdUtc) {
    }

    /** 核销明细：窗口全部凭证 + 全部更正，按发生顺序返回，只读。 */
    public record SettlementHistoryResponse(long windowId, List<SettlementResponse> settlements,
                                            List<CorrectionResponse> corrections) {
    }

    /**
     * 单申请核销诊断视图：给出持有额度、已结算送达/损耗、剩余可核销额度（实际值），
     * 以及在指定送达日与率下试算一笔核销的要求扣减与差额（不落库、不改变状态）。
     */
    public record SettlementDiagnosticResponse(String allocationKey, long windowId, String status,
                                               String heldAmount, String settledDelivered,
                                               String settledLoss, String remainingSettleable,
                                               String deliveryDate, String rate, String rateVersion,
                                               String trialDelivered, String trialLoss,
                                               String trialDeduction, String shortfall) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
