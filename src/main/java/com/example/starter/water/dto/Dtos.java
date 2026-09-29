package com.example.starter.water.dto;

import java.util.List;

/**
 * 灌区配水 API 请求/响应契约。水量在 API 层一律使用十进制字符串（单位立方米，最多 3 位小数），
 * 降雨量单位毫米（最多 3 位小数），抵扣系数单位立方米/毫米（最多 6 位小数），
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
     * 转出/核销时等额扣减，取消时归零；持有额度恰为零的已转出或已核销申请仍为 APPROVED）。
     * creditCoefficient 为降雨抵扣系数（立方米/毫米），null 表示未声明。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String creditCoefficient, String requester,
                                     String status, String createdUtc, String updatedUtc) {
    }

    /** 仅含幂等键的命令（批准/取消申请、取消限供、撤销降雨）。 */
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

    /** 登记窗口降雨记录命令；降雨量单位毫米，必须大于 0（负数返回 422）。 */
    public record RainfallRegisterRequest(String commandKey, String rainKey, String rainfallMm) {
    }

    /** 降雨确认命令；roleId 为水务角色 ID，同一记录须两名不同角色确认。 */
    public record RainfallConfirmRequest(String commandKey, String roleId) {
    }

    /**
     * 降雨记录视图。状态：PENDING 待生效 / EFFECTIVE 已生效 / REVOKED 已撤销。
     * effectiveUtc 为第二名不同角色确认时刻，未生效为 null；
     * revokedUtc/revokedBy 撤销后才有值；confirmations 为已确认角色明细。
     */
    public record RainfallResponse(long id, String rainKey, long windowId, String rainfallMm, String status,
                                   String registrar, int confirmCount, int requiredCount,
                                   List<RainfallConfirmationResponse> confirmations,
                                   String createdUtc, String effectiveUtc, String revokedUtc, String revokedBy) {
    }

    /** 降雨确认明细视图；同一降雨记录每个角色至多一条。 */
    public record RainfallConfirmationResponse(String roleId, String confirmer, String createdUtc) {
    }

    /** 窗口降雨记录列表视图，按登记顺序（主键升序）。 */
    public record RainfallListResponse(long windowId, List<RainfallResponse> records) {
    }

    /** 声明申请的降雨抵扣系数命令；coefficient 单位立方米/毫米，不允许为负（负数返回 422）。 */
    public record CreditFactorRequest(String commandKey, String coefficient) {
    }

    /** 单笔核销命令；amount 为本次请求核销量，单位立方米。 */
    public record WriteOffRequest(String commandKey, String writeOffKey, String allocationKey, String amount) {
    }

    /** 批量核销中的单项；writeOffKey 全局唯一。 */
    public record WriteOffItem(String writeOffKey, String allocationKey, String amount) {
    }

    /** 批量核销命令；所有项必须属于路径指定的同一窗口，先验最终余额再整体提交。 */
    public record WriteOffBatchRequest(String commandKey, String batchKey, List<WriteOffItem> items) {
    }

    /** 核销时固化的降雨记录证据快照，记录事后撤销也不改写。 */
    public record RainSnapshotResponse(String rainKey, String rainfallMm) {
    }

    /**
     * 核销流水视图，创建后不可变。
     * requestedAmount 为本次请求核销量（原核销量）；rainfallMm/coefficient 为核销时固化的
     * 有效降雨量与申请系数；creditAmount 为降雨抵扣（不超过本次核销量）；
     * quotaDeduction 为配额实际扣减；守恒：creditAmount + quotaDeduction = requestedAmount。
     * batchKey 单笔核销时为 null。
     */
    public record WriteOffResponse(String writeOffKey, String batchKey, long windowId, String allocationKey,
                                   String requestedAmount, String rainfallMm, String coefficient,
                                   String creditAmount, String quotaDeduction,
                                   String heldBefore, String heldAfter, String actor, String createdUtc,
                                   List<RainSnapshotResponse> rainRecords) {
    }

    /** 窗口核销流水列表视图，按核销顺序（主键升序）。 */
    public record WriteOffListResponse(long windowId, List<WriteOffResponse> writeOffs) {
    }

    /** 批量核销结果：逐项结果与窗口级合计，整体成功或整体回滚。 */
    public record WriteOffBatchResponse(String batchKey, long windowId, List<WriteOffResponse> items,
                                        String requestedTotal, String creditTotal, String quotaDeductionTotal) {
    }

    /** 单申请核销诊断行：当前系数、窗口有效降雨、可用/已用抵扣、已扣配额与核销笔数。 */
    public record AllocationDiagnosticResponse(String allocationKey, String status, String heldAmount,
                                               String creditCoefficient, String effectiveRainfallMm,
                                               String availableCredit, String usedCredit,
                                               String quotaDeductedTotal, int writeOffCount) {
    }

    /** 窗口诊断视图：有效降雨、核销合计与逐申请诊断，按申请主键升序稳定排序。 */
    public record DiagnosticsResponse(long windowId, String effectiveRainfallMm, String requestedTotal,
                                      String creditTotal, String quotaDeductionTotal,
                                      List<AllocationDiagnosticResponse> allocations) {
    }

    /** 窗口历史明细：窗口本身 + 全部申请 + 全部限供 + 全部降雨记录 + 全部核销流水。 */
    public record HistoryResponse(WindowResponse window, List<AllocationResponse> allocations,
                                  List<CurtailmentResponse> curtailments,
                                  List<RainfallResponse> rainfallRecords,
                                  List<WriteOffResponse> writeOffs) {
    }

    /**
     * 统一错误响应体。actualValue/requiredValue/difference 仅在涉及数量、时长或比例的失败时出现，
     * 分别为实际值、要求值与差额（要求值减实际值），其余场景为 null。
     */
    public record ErrorResponse(String code, String message, String actualValue, String requiredValue,
                                String difference) {
        public ErrorResponse(String code, String message) {
            this(code, message, null, null, null);
        }
    }
}
