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

    /**
     * 渠道日蒸发率登记/修改命令体，渠道与 UTC 日由路径参数给出。
     * rate 为四位小数字符串比例，取值 0~0.2000。当日首次核销前可修改，之后修改返回 409。
     */
    public record EvaporationRateRequest(String commandKey, String rate) {
    }

    /** 渠道日蒸发率视图；firstSettlementUtc 非空后该日率冻结，修改将返回 409。 */
    public record EvaporationRateResponse(String channelId, String dayUtc, String rate, long version,
                                          String createdUtc, String updatedUtc, String firstSettlementUtc,
                                          boolean frozen) {
    }

    /** 单笔送达核销命令；dayUtc 缺省时按 occurredUtc 所属 UTC 自然日取率。 */
    public record SettlementRequest(String commandKey, String settlementKey, Long windowId,
                                    String allocationKey, String occurredUtc, String deliveredVolume,
                                    String downstreamUserId, String dayUtc) {
    }

    /** 批量核销中的一笔明细；settlementKey 与 dayUtc 语义同单笔。 */
    public record SettlementItemRequest(String settlementKey, String allocationKey, String occurredUtc,
                                        String deliveredVolume, String downstreamUserId, String dayUtc) {
    }

    /** 批量送达核销命令：按完整最终余额与逐笔舍入口径预校验，任一失败整批回滚。 */
    public record SettlementBatchRequest(String commandKey, String batchKey, Long windowId,
                                         List<SettlementItemRequest> items) {
    }

    /**
     * 核销记录视图（不可变证据）。deliveredVolume 为固化的原始送达量（请求原值，最多三位小数）；
     * settledDeliveredVolume 为原始送达量银行家舍入到两位小数后的计入送达；
     * lossVolume 为 round2(送达量*率)，upstreamDeduction 为 round2(送达量*(1+率))；
     * 恒满足 upstreamDeduction = settledDeliveredVolume + lossVolume，否则该笔在提交前以 422 拒绝。
     * correctionKey 非 null 表示该核销已被反向更正。
     */
    public record SettlementResponse(String settlementKey, String batchKey, long windowId, String channelId,
                                     String allocationKey, String downstreamUserId, String dayUtc,
                                     String occurredUtc, String deliveredVolume, String settledDeliveredVolume,
                                     String lossVolume, String upstreamDeduction, String rate, long rateVersion,
                                     String status, String actor, String createdUtc, String correctionKey) {
    }

    /** 单笔核销响应包装。 */
    public record SettlementResultResponse(SettlementResponse settlement, String upstreamRemaining,
                                           String upstreamRequired) {
    }

    /** 批量核销响应：全部成功时返回逐笔结果及上游最终余额。 */
    public record SettlementBatchResponse(String batchKey, long windowId, List<SettlementResponse> settlements,
                                          List<UpstreamBalance> finalBalances) {
    }

    /** 批量核销后某上游申请的最终持有余额。 */
    public record UpstreamBalance(String allocationKey, String remaining, String required, String deducted) {
    }

    /** 核销反向更正命令（只追加，回补上游持有额度）。 */
    public record SettlementReversalRequest(String commandKey, String correctionKey, String settlementKey,
                                            String reason) {
    }

    /** 反向更正视图；restoredUpstream 单位立方米，恒等于原上游扣减。 */
    public record SettlementReversalResponse(String correctionKey, long originalSettlementId,
                                             String originalSettlementKey, String batchKey, long windowId,
                                             String channelId, String allocationKey, String downstreamUserId,
                                             String dayUtc, String reversedDelivered,
                                             String reversedSettledDelivered, String reversedLoss,
                                             String restoredUpstream, String rate, long rateVersion,
                                             String reason, String actor, String createdUtc) {
    }

    /** 核销明细列表（窗口或申请维度）。 */
    public record SettlementListResponse(long windowId, List<SettlementResponse> settlements,
                                         List<SettlementReversalResponse> corrections) {
    }

    /** 单条日蒸发率诊断：率值、版本及该日冻结状态。 */
    public record RateDiagnostic(String channelId, String dayUtc, String rate, long version,
                                 String firstSettlementUtc, boolean frozen) {
    }

    /** 渠道蒸发率诊断列表，按 UTC 日升序。 */
    public record RateDiagnosticResponse(String channelId, List<RateDiagnostic> rates) {
    }

    /**
     * 统一错误响应体。数量/时长类错误给出 actual（实际值）、required（要求值）或 delta（差额）；
     * itemIndex 标识批量失败明细（从 0 开始），单笔场景为 null。
     */
    public record ErrorResponse(String code, String message, String actual, String required, String delta,
                                Integer itemIndex) {
    }
}
