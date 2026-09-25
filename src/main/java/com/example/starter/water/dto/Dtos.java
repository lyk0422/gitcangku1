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
     * 转出时等额扣减，取消时归零，计量更正时改写为校正数量；持有额度恰为零的已转出申请仍为 APPROVED）。
     * version 为核销版本：批准即版本 1，每次批准或撤销计量更正后递增。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status, long version,
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
     * 登记计量更正命令。meterKey 为幂等指纹键：同键且原核销版本、校正数、读表时刻、原因、操作者
     * 完全一致时重放首次结果，同键改参返回 409，校验失败不占键。
     */
    public record SubmitCorrectionRequest(String meterKey, String allocationKey, Long baseVersion,
                                          String correctedAmount, String readingUtc, String reason) {
    }

    /**
     * 计量更正视图。状态：REQUESTED 已登记 / APPROVED 已批准 / REVOKED 已撤销。
     * previousAmount 为批准时记录的原持有额度（撤销时恢复），未批准为 null。
     */
    public record CorrectionResponse(String meterKey, String allocationKey, long windowId, long baseVersion,
                                     String previousAmount, String correctedAmount, String readingUtc,
                                     String reason, String actor, String status,
                                     String createdUtc, String updatedUtc) {
    }

    /** 批量批准计量更正命令；批内按更正提交顺序裁决，任一失败则全部回滚。 */
    public record ApproveCorrectionsRequest(String commandKey, List<String> meterKeys) {
    }

    /** 批量批准结果视图，按更正提交顺序返回。 */
    public record CorrectionBatchResponse(List<CorrectionResponse> corrections) {
    }

    /** 批量批准预检命令（不落库），用于查询每个更正的拒绝原因。 */
    public record PreviewCorrectionsRequest(List<String> meterKeys) {
    }

    /** 单个更正的预检结果：ok 为 false 时 code/message 给出可区分的拒绝原因。 */
    public record CorrectionPreviewItem(String meterKey, boolean ok, String code, String message) {
    }

    /** 批量预检结果视图。 */
    public record CorrectionPreviewResponse(List<CorrectionPreviewItem> items) {
    }

    /** 不可变读表快照视图，批准更正时创建。 */
    public record SnapshotResponse(String meterKey, String allocationKey, long windowId, String correctedAmount,
                                   String readingUtc, String reason, String actor, String createdUtc) {
    }

    /**
     * 额度流水视图。entryType：WRITE_OFF 原核销 / TRANSFER_OUT 转出 / CANCEL 取消 /
     * CORRECTION 更正反向流水 / REVERSAL 撤销反向流水；meterKey 仅更正类流水非空。
     */
    public record LedgerEntryResponse(long id, String entryType, String meterKey, String delta,
                                      String balanceAfter, String createdUtc) {
    }

    /** 核销记录余额演算视图：按入账顺序的全部流水，balanceAfter 即每步后的持有额度。 */
    public record AllocationLedgerResponse(String allocationKey, List<LedgerEntryResponse> entries) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
