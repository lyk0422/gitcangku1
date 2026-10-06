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

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }

    /** 创建渠段命令；操作人由 X-Actor-Id 请求头提供，resistance 为正整数。 */
    public record CreateSegmentRequest(String requestId, String segmentKey, Long resistance) {
    }

    /** 渠段视图；version 从 1 起，每次成功修改阻力 +1。 */
    public record SegmentResponse(String segmentKey, long resistance, int version,
                                  String createdUtc, String updatedUtc) {
    }

    /** 修改渠段阻力命令；expectedVersion 为期望的当前版本，不一致返回 409。 */
    public record UpdateResistanceRequest(String requestId, Long resistance, Integer expectedVersion) {
    }

    /**
     * 提交水力型配水申请命令；操作人由 X-Actor-Id 请求头提供。
     * path 为 1 至 5 段有序无重复渠段业务键；flowRate 为恒定正整数流量（立方米/分钟）；
     * 供水时段为 UTC 整分钟左闭右开区间；availableHead 为正整数可用水头。
     * 原申请水量 = flowRate × 时段分钟数。
     */
    public record SubmitHydraulicRequest(String requestId, String allocationKey, Long windowId, String userId,
                                         List<String> path, Long flowRate, String supplyStartUtc,
                                         String supplyEndUtc, Long availableHead) {
    }

    /** 水力型申请视图；amount 为派生的原申请水量（流量 × 分钟数）。 */
    public record HydraulicAllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                              List<String> path, long flowRate, String supplyStartUtc,
                                              String supplyEndUtc, long availableHead, String requester,
                                              String status, String createdUtc) {
    }

    /** 冻结时片视图：某渠段在某时片的同时总流量、核算阻力参数与段损失。 */
    public record SliceView(String segmentKey, String sliceStartUtc, String sliceEndUtc, long totalFlow,
                            long resistance, int resistanceVersion, long loss) {
    }

    /** 水力型申请明细：冻结路径、恒定流量曲线、可用水头与审批冻结的时片快照。 */
    public record HydraulicDetailResponse(String allocationKey, List<String> path, long flowRate,
                                          String supplyStartUtc, String supplyEndUtc, long availableHead,
                                          String status, List<SliceView> slices) {
    }
}
