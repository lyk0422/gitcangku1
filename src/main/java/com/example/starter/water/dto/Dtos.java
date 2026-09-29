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

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。creditCoefficient 为每毫米降雨抵扣系数，缺省/null 表示不参与抵扣。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, String creditCoefficient) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，降雨核销只扣减配额部分，取消时归零；持有额度恰为零的已转出/已核销申请仍为 APPROVED）。
     * creditCoefficient 为每毫米有效降雨抵扣系数（立方米/毫米），null 表示该申请不参与降雨抵扣。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
                                     String createdUtc, String updatedUtc, String creditCoefficient) {
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

    /** 窗口历史明细：窗口本身 + 全部申请 + 全部限供 + 全部降雨记录 + 全部核销单。 */
    public record HistoryResponse(WindowResponse window, List<AllocationResponse> allocations,
                                  List<CurtailmentResponse> curtailments,
                                  List<RainRecordResponse> rainRecords,
                                  List<WriteoffResponse> writeoffs) {
    }

    /** 登记窗口降雨记录命令；登记人由 X-Actor-Id 请求头提供，降雨时刻须落在窗口 [startUtc,endUtc)。 */
    public record RainRegisterRequest(String commandKey, String rainKey, String rainMillis, String observedUtc) {
    }

    /** 确认降雨命令（仅含幂等键）；确认水务角色由 X-Actor-Id 请求头提供，两名不同角色确认后生效。 */
    public record RainConfirmRequest(String commandKey) {
    }

    /** 撤销降雨命令（仅含幂等键）；撤销人由 X-Actor-Id 请求头提供，已结算核销不受影响。 */
    public record RainRevokeRequest(String commandKey) {
    }

    /** 降雨确认证据视图，不可变。 */
    public record RainConfirmationView(String confirmer, String confirmedUtc) {
    }

    /**
     * 降雨记录视图。状态：PENDING 待生效 / EFFECTIVE 已生效 / REVOKED 已撤销。
     * rainMillis 为经复核的有效降雨量（毫米）；observedUtc 为降雨观测时刻（UTC）；
     * effectiveUtc 为第二名不同水务角色确认时刻，未生效/已撤销时语义见各字段 null 含义；
     * revokedUtc/revokedBy 未撤销时为 null；confirmations 按确认先后稳定排序。
     */
    public record RainRecordResponse(String rainKey, long windowId, String rainMillis, String observedUtc,
                                     String status, String registeredBy, String createdUtc,
                                     String effectiveUtc, String revokedUtc, String revokedBy,
                                     List<RainConfirmationView> confirmations, int requiredConfirmations) {
    }

    /** 窗口降雨记录列表，按登记先后（主键升序）稳定排序。 */
    public record RainListResponse(long windowId, List<RainRecordResponse> records) {
    }

    /** 单笔核销明细请求：对指定 APPROVED 申请核销 amount 立方米。 */
    public record WriteoffItemRequest(String allocationKey, String amount) {
    }

    /** 核销命令（单笔或批量，按 items 顺序稳定排序）；操作人由 X-Actor-Id 请求头提供。 */
    public record WriteoffRequest(String commandKey, String writeoffKey, List<WriteoffItemRequest> items) {
    }

    /** 核销明细中参与抵扣的降雨证据快照（不可变）。 */
    public record AppliedRainView(String rainKey, String rainMillis, String credit) {
    }

    /**
     * 核销明细视图（不可变快照）。守恒：credit + quotaDeduction = amount；
     * coefficient 为结算时申请的每毫米抵扣系数（立方米/毫米），null 表示不参与抵扣；
     * appliedRains 为当时参与抵扣的 EFFECTIVE 降雨证据集合，按业务键排序。
     */
    public record WriteoffItemResponse(int seq, String allocationKey, String amount, String credit,
                                       String quotaDeduction, String coefficient,
                                       List<AppliedRainView> appliedRains) {
    }

    /** 核销单视图（不可变）。守恒：totalCredit + totalQuotaDeduction = totalAmount，totalCredit <= totalAmount。 */
    public record WriteoffResponse(String writeoffKey, long windowId, String totalAmount, String totalCredit,
                                   String totalQuotaDeduction, String status, String actor, String createdUtc,
                                   List<WriteoffItemResponse> items) {
    }

    /** 申请维度降雨抵扣诊断；预览按当前持有额度核销时的实际抵扣/配额扣减/差额。 */
    public record AllocationDiagnostic(String allocationKey, String userId, String status, String heldAmount,
                                       String creditCoefficient, String effectiveRainMillis,
                                       String creditPreview, String quotaDeductionPreview,
                                       String shortfallPreview) {
    }

    /** 窗口降雨与核销诊断视图；金额单位立方米，雨量单位毫米，时间均为 UTC。 */
    public record RainDiagnosticsResponse(long windowId, String plannedVolume, String activeCurtailmentVolume,
                                          String availableTotal, String approvedTotal, String remaining,
                                          String effectiveRainMillis, List<RainRecordResponse> effectiveRain,
                                          List<AllocationDiagnostic> allocations) {
    }

    /** 统一错误响应体；details 为可区分的逐项失败原因（如批量核销每项余额校验），无细节时为 null。 */
    public record ErrorResponse(String code, String message, List<ErrorDetail> details) {

        public ErrorResponse(String code, String message) {
            this(code, message, null);
        }
    }

    /** 错误明细：item 为批量请求中的序号（从 0 起），allocationKey 标识对象，reason 为可区分错误码。 */
    public record ErrorDetail(int item, String allocationKey, String code, String message,
                              String requested, String actual, String difference) {
    }
}
