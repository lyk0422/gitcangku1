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

    /**
     * 窗口当前可用容量视图；approvedTotal 仅汇总普通 APPROVED 申请的当前持有额度，
     * emergencyOutstandingTotal 为紧急启用转入且尚未核销/归还的预占量；
     * remaining 为同时扣除两类占用后的余量。该接口不暴露储备账本总量与剩余。
     */
    public record CapacityResponse(long windowId, String plannedVolume, String activeCurtailmentVolume,
                                   String availableTotal, String approvedTotal,
                                   String emergencyOutstandingTotal, String remaining) {
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

    // ------------------------------------------------------------------
    // 紧急储备
    // ------------------------------------------------------------------

    /** 登记紧急事件命令；只有 VALID 事件可以申请储备水。 */
    public record EmergencyEventRequest(String commandKey, String eventKey, String title) {
    }

    /** 紧急事件视图。状态：VALID 有效 / INVALID 非紧急或已作废；invalidatedUtc 未作废时为 null。 */
    public record EmergencyEventResponse(String eventKey, String title, String status,
                                         String createdUtc, String invalidatedUtc) {
    }

    /** 紧急事件明细：事件本身 + 其全部启用请求（含当时两位确认人）。 */
    public record EmergencyEventDetailResponse(EmergencyEventResponse event,
                                               List<EmergencyActivationResponse> requests) {
    }

    /**
     * 双角色确认启用命令。角色由 X-Role-Id 请求头提供，确认人由 X-Actor-Id 请求头提供；
     * 同一 requestId 首位确认建 PENDING，第二位不同角色/不同确认人确认后 ACTIVE。
     */
    public record EmergencyActivationRequest(String commandKey, String requestId, String eventKey,
                                             Long windowId, String amount) {
    }

    /** 一位确认人视图；seq 为 1/2。 */
    public record EmergencyConfirmationView(int seq, String roleId, String confirmer, String confirmedUtc) {
    }

    /**
     * 紧急启用请求视图。状态：PENDING 待第二位确认（未扣储备）/ ACTIVE 已启用有未结清量 /
     * SETTLED 已全部核销或归还。amount 为首次启用数量；writtenOffAmount 累计核销（不回补储备）、
     * returnedAmount 累计归还（回补储备），outstandingAmount 为尚未核销且尚未归还的数量。
     * reserveRemaining 为查询时刻储备账本剩余。
     */
    public record EmergencyActivationResponse(String requestId, String eventKey, long windowId, String status,
                                              String amount, String writtenOffAmount, String returnedAmount,
                                              String outstandingAmount, String reserveRemaining,
                                              List<EmergencyConfirmationView> confirmations,
                                              String createdUtc, String activatedUtc) {
    }

    /** 核销/归还命令体；数量大于 0 且不超过当前未结清量。 */
    public record EmergencySettlementRequest(String commandKey, String amount) {
    }

    /** 紧急储备不可变流水视图。flowType：ACTIVATION 启用 / WRITEOFF 核销 / RETURN 归还。 */
    public record EmergencyFlowResponse(long flowId, String requestId, String eventKey, long windowId,
                                        String flowType, String amount, String actor, String createdUtc) {
    }

    /** 某启用请求的全部流水（启用、核销、归还），按发生顺序返回。 */
    public record EmergencyFlowListResponse(String requestId, List<EmergencyFlowResponse> flows) {
    }

    /**
     * 储备账本视图（独立账本，普通排班不可读取）。
     * 恒等式：total = remaining + activated - returned（核销不回补）；
     * outstanding = activated - writtenOff - returned 为全部启用单未结清量之和。
     */
    public record ReserveResponse(String totalVolume, String remainingVolume, String activatedVolume,
                                  String writtenOffVolume, String returnedVolume, String outstandingVolume,
                                  String updatedUtc) {
    }

    /** 目标窗口的紧急水预占视图：未结清量与逐笔启用单。 */
    public record WindowEmergencyResponse(long windowId, String outstandingVolume,
                                          List<EmergencyActivationResponse> requests) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
