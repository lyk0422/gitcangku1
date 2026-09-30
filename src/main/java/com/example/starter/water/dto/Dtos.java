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

    /**
     * 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。
     * scheduledStartUtc/scheduledEndUtc 同时给出时该申请携带排班时段（UTC 左闭右开），
     * 必须落在所属供水窗口内且不与同灌渠其他排班占用重叠；同时为空表示不排班。
     */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount,
                                          String scheduledStartUtc, String scheduledEndUtc) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，取消时归零；持有额度恰为零的已转出申请仍为 APPROVED）。
     * scheduledStartUtc/scheduledEndUtc 为当前排班时段（中断顺延后为新窗口），未排班时均为 null。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
                                     String scheduledStartUtc, String scheduledEndUtc,
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
     * 灌渠中断登记命令。startUtc/endUtc 为中断窗口（UTC 左闭右开）；postponeMinutes 为对所有
     * 受影响且未开始排班统一顺延的分钟数；同 requestId 重放必须携带相同顺延分钟数。
     */
    public record RegisterOutageRequest(String commandKey, String requestId, String channelId,
                                        String startUtc, String endUtc, Integer postponeMinutes,
                                        String reason) {
    }

    /** 顺延对应关系视图：原排班、新窗口、中断原因与顺延分钟数一一对应。 */
    public record PostponementResponse(String allocationKey, String originalStartUtc, String originalEndUtc,
                                       String newStartUtc, String newEndUtc, Integer postponeMinutes,
                                       String reason) {
    }

    /** 灌渠中断视图：中断窗口本身 + 本次登记产生的全部顺延对应关系（按申请顺序）。 */
    public record OutageResponse(String requestId, String channelId, String reason, String startUtc,
                                 String endUtc, Integer postponeMinutes, String status, String createdUtc,
                                 List<PostponementResponse> postponements) {
    }

    /** 灌渠中断列表视图，按登记顺序返回。 */
    public record OutageListResponse(String channelId, List<OutageResponse> outages) {
    }

    /** 用水核销命令；操作人由 X-Actor-Id 请求头提供。 */
    public record SettleUsageRequest(String commandKey, String usageKey, String volume) {
    }

    /**
     * 用水核销视图。scheduleStartUtc/scheduleEndUtc 为核销当时的排班快照（未排班为 null），
     * 排班随后顺延不会改写已完成核销记录。
     */
    public record SettlementResponse(String usageKey, String allocationKey, long windowId, String volume,
                                     String scheduleStartUtc, String scheduleEndUtc, String actor,
                                     String createdUtc) {
    }

    /** 申请的历史核销列表（不可变），按核销顺序返回。 */
    public record SettlementListResponse(String allocationKey, List<SettlementResponse> settlements) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
