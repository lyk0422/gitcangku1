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

    /** 发布排班命令；排班渠道取自所属供水窗口，时间按 UTC 左闭右开。 */
    public record PublishScheduleRequest(String commandKey, String scheduleKey, Long windowId,
                                         String startUtc, String endUtc) {
    }

    /** 登记灌渠中断命令；postponeMinutes 对所有受影响且未开始的排班统一顺延。requestId 即 commandKey。 */
    public record RegisterOutageRequest(String commandKey, String outageKey, String channelId,
                                        String startUtc, String endUtc, String reason, Integer postponeMinutes) {
    }

    /** 历史用水核销命令；核销时对排班当时窗口做不可变快照。 */
    public record SettleUsageRequest(String commandKey, String settlementKey, String volume) {
    }

    /**
     * 单次顺延对应关系：from 为本次中断前排班窗口，to 为顺延后窗口；
     * original 为排班发布时的原始窗口（多次顺延时保持不变）。
     */
    public record PostponementItem(String outageKey, String reason, int postponeMinutes,
                                   String originalStartUtc, String originalEndUtc,
                                   String fromStartUtc, String fromEndUtc,
                                   String toStartUtc, String toEndUtc, String createdUtc) {
    }

    /**
     * 排班视图。status：SCHEDULED 未开始或进行中 / COMPLETED 已核销；
     * started 按可控当前时刻与当前窗口起点判断；startUtc/endUtc 为当前生效窗口，
     * originalStartUtc/originalEndUtc 为发布时原始窗口，postponements 为历次顺延链。
     */
    public record ScheduleResponse(String scheduleKey, long windowId, String channelId,
                                   String originalStartUtc, String originalEndUtc,
                                   String startUtc, String endUtc, String status, boolean started,
                                   String createdUtc, String updatedUtc, List<PostponementItem> postponements) {
    }

    /** 中断视图；affected 为首次登记时受影响且顺延的排班及顺延对应关系，重放返回首次结果。 */
    public record OutageResponse(String outageKey, String channelId, String startUtc, String endUtc,
                                 String reason, int postponeMinutes, String status, String createdUtc,
                                 List<AffectedSchedule> affected) {
    }

    /** 受中断影响的排班：原排班窗口、本次顺延前后窗口与顺延分钟数。 */
    public record AffectedSchedule(String scheduleKey, String originalStartUtc, String originalEndUtc,
                                   String previousStartUtc, String previousEndUtc,
                                   String newStartUtc, String newEndUtc, int postponeMinutes) {
    }

    /** 用水核销视图；scheduled* 为核销当时排班窗口快照，之后排班再顺延也不改写。 */
    public record SettlementResponse(String settlementKey, String scheduleKey, String scheduledStartUtc,
                                     String scheduledEndUtc, String volume, String status, String createdUtc) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
