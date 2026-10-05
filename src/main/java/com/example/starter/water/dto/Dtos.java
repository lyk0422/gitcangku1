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
     * 转出时等额扣减，取消时仅保留累计发出量；持有额度恰为零的已转出申请仍为 APPROVED）。
     * version 为乐观锁版本，发出交付时作为 expectedVersion 提交。
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
     * 渠段在途交付发出命令。requestId 为幂等键；expectedVersion 为绑定申请当前的乐观锁版本；
     * dispatchUtc 为客户端指定的发出时刻（UTC），旅时 travelMinutes 1 至 120 分钟、创建后不可变，
     * 到水时刻 = 发出时刻 + 旅时。
     */
    public record DispatchDeliveryRequest(String requestId, String deliveryKey, String allocationKey,
                                          String volume, String dispatchUtc, Integer travelMinutes,
                                          Long expectedVersion) {
    }

    /**
     * 到水确认命令。requestId 为幂等键；expectedVersion 为交付单当前乐观锁版本；
     * receivedVolume 为实际收到水量（0 至发出量，单位立方米，最多 3 位小数），差额记损失，
     * 收到 0 记 LOST，否则 RECEIVED；仅在服务端时钟达到到水时刻后允许。
     */
    public record ConfirmDeliveryRequest(String requestId, String receivedVolume, Long expectedVersion) {
    }

    /**
     * 交付单视图。状态：IN_TRANSIT / RECEIVED / LOST。
     * receivedVolume、lostVolume、confirmedUtc 在未确认时为 null；确认结单后不可变。
     */
    public record DeliveryResponse(String deliveryKey, String allocationKey, long windowId, String volume,
                                   String dispatchUtc, int travelMinutes, String arrivalUtc, String status,
                                   String receivedVolume, String lostVolume, String confirmedUtc,
                                   long version, String actor, String createdUtc) {
    }

    /**
     * 申请交付汇总视图：approved 为当前批准（持有）额度，dispatched 为累计发出量，
     * undispatched = approved - dispatched（未发量），inTransit/received/lost 分别汇总在途、
     * 已收到与损失水量；满足 dispatched = inTransit + received + lost，损失不返还未发量。
     */
    public record DeliverySummaryResponse(String allocationKey, String approved, String dispatched,
                                          String undispatched, String inTransit, String received,
                                          String lost, List<DeliveryResponse> deliveries) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
