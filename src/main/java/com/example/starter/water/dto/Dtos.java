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

    /** 创建渠段命令；操作人由 X-Actor-Id 请求头提供，阻力版本从 1 起。 */
    public record CreateSegmentRequest(String requestId, String segmentKey, Long resistance) {
    }

    /** 修改渠段阻力命令；expectedVersion 须等于当前版本。 */
    public record UpdateResistanceRequest(String requestId, Long resistance, Long expectedVersion) {
    }

    /** 渠段视图；resistance 为正整数阻力系数，version 为当前阻力版本。 */
    public record SegmentResponse(String segmentKey, long resistance, int version,
                                  String createdUtc, String updatedUtc) {
    }

    /**
     * 新类型配水申请命令；操作人由 X-Actor-Id 请求头提供。
     * segments 为 1..5 段有序无重复渠段；flow 为恒定正整数流量（立方米/分钟）；
     * 时段为 UTC 整分钟左闭右开；流量 × 分钟数 = 原申请水量；availableHead 为正整数可用水头。
     */
    public record SubmitPathAllocationRequest(String requestId, String allocationKey, Long windowId,
                                              String userId, Long flow, String startUtc, String endUtc,
                                              Long availableHead, List<String> segments) {
    }

    /** 新类型申请视图；amount 为派生原申请水量（流量 × 分钟数）的十进制字符串。 */
    public record PathAllocationResponse(String allocationKey, long windowId, String userId,
                                         String amount, String status, long flow, String startUtc,
                                         String endUtc, long availableHead, List<String> segments,
                                         String createdUtc) {
    }

    /** 审批冻结的段参数快照视图。 */
    public record FrozenSegmentView(String segmentKey, int seq, long resistance, int version) {
    }

    /** 审批冻结的时片总流量视图，时片为 UTC 左闭右开。 */
    public record FrozenSliceView(String segmentKey, String startUtc, String endUtc, long totalFlow) {
    }

    /** 申请路径视图；frozen 为 false 时冻结快照为空列表。 */
    public record AllocationPathResponse(String allocationKey, long flow, String startUtc,
                                         String endUtc, long availableHead, List<String> segments,
                                         boolean frozen, List<FrozenSegmentView> frozenSegments,
                                         List<FrozenSliceView> frozenSlices) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
