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

    /** 核销命令；操作人由 X-Actor-Id 请求头提供，meterUtc 为 UTC 读表时刻。 */
    public record WriteoffRequest(String commandKey, String writeoffKey, String amount, String meterUtc) {
    }

    /** 核销记录视图（原流水），创建后不可变；version 为同一申请内自 1 递增的核销版本。 */
    public record WriteoffResponse(String writeoffKey, String allocationKey, long windowId, int version,
                                   String amount, String meterUtc, String actor, String createdUtc) {
    }

    /** 核销记录（原流水）列表视图。 */
    public record WriteoffListResponse(String allocationKey, List<WriteoffResponse> writeoffs) {
    }

    /** 计量更正登记命令；操作人由 X-Actor-Id 请求头提供。correctedAmount 不得为负，最多 3 位小数。 */
    public record CorrectionRequest(String meterKey, String writeoffKey, Integer originalVersion,
                                    String correctedAmount, String meterUtc, String reason) {
    }

    /**
     * 计量更正视图。状态：REQUESTED 已登记 / APPROVED 已批准 / REVOKED 已撤销。
     * decidedUtc 未裁决时为 null。
     */
    public record CorrectionResponse(String meterKey, String writeoffKey, String allocationKey, long windowId,
                                     int originalVersion, String correctedAmount, String meterUtc, String reason,
                                     String actor, String status, String createdUtc, String decidedUtc) {
    }

    /** 批量批准更正命令；meterKeys 按提交顺序裁决，任一失败全部回滚。 */
    public record CorrectionApproveRequest(String commandKey, List<String> meterKeys) {
    }

    /** 批量批准结果视图，按提交顺序返回已批准更正。 */
    public record CorrectionApproveResponse(List<CorrectionResponse> approved) {
    }

    /** 结算流水视图。kind：WRITEOFF 原核销流水 / CORRECTION 更正反向流水 / REVOCATION 撤销反向流水。 */
    public record LedgerEntryResponse(long id, String kind, String refKey, String delta, String balanceAfter,
                                      String eventUtc, String createdUtc) {
    }

    /** 反向流水列表视图（仅 CORRECTION/REVOCATION 条目）。 */
    public record LedgerResponse(String allocationKey, List<LedgerEntryResponse> entries) {
    }

    /** 余额演算事件视图：按业务事件时刻排列的余额变化。 */
    public record BalanceEventResponse(String eventUtc, String kind, String refKey, String delta,
                                       String balance) {
    }

    /** 余额演算视图：自批准额度起，逐事件重放后的余额曲线。 */
    public record BalanceEvolutionResponse(String allocationKey, String startBalance,
                                           List<BalanceEventResponse> events, String finalBalance) {
    }

    /** 拒绝原因视图。 */
    public record RejectionResponse(long id, Long windowId, String meterKey, String operation, String code,
                                    String message, String createdUtc) {
    }

    /** 窗口拒绝原因列表视图。 */
    public record RejectionListResponse(long windowId, List<RejectionResponse> rejections) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
