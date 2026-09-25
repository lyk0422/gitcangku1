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

    /** 轮灌排班命令：为 APPROVED 申请在所属窗口内申请一个引水时段（左闭右开，30 至 720 分钟）。 */
    public record CreateScheduleRequest(String commandKey, String scheduleKey, String allocationKey,
                                        String startUtc, String endUtc) {
    }

    /**
     * 排班记录视图。状态：ACTIVE / CANCELLED；cancelledUtc 未取消时为 null。
     * remainingSnapshot 为排班时申请剩余未核销水量快照，后续核销不改写。
     */
    public record ScheduleResponse(long id, String scheduleKey, String channelId, String allocationKey,
                                   long windowId, String startUtc, String endUtc, String remainingSnapshot,
                                   String status, String createdUtc, String cancelledUtc) {
    }

    /** 渠道排班表视图（含已取消的历史记录，按开始时刻升序）。 */
    public record ChannelScheduleListResponse(String channelId, List<ScheduleResponse> schedules) {
    }

    /** 申请时段明细视图（含已取消的历史记录，按开始时刻升序）。 */
    public record AllocationScheduleListResponse(String allocationKey, List<ScheduleResponse> schedules) {
    }

    /** 用水核销命令：occurredUtc 必须落在该申请某个 ACTIVE 时段内。 */
    public record ConsumptionRequest(String commandKey, String amount, String occurredUtc) {
    }

    /** 核销记录视图；remainingAfter 为本次核销后的剩余未核销水量。 */
    public record ConsumptionResponse(long id, String allocationKey, long windowId, String amount,
                                      String occurredUtc, String remainingAfter, String createdUtc) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
