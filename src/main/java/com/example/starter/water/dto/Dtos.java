package com.example.starter.water.dto;

import java.util.List;

/**
 * 灌区配水 API 请求/响应契约。水量在 API 层一律使用十进制字符串（单位立方米，最多 3 位小数），
 * 时间使用 ISO-8601 UTC 字符串。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 创建供水窗口命令；pumpStationKey 可空，非空时窗口接入该泵站瞬时流量约束。 */
    public record CreateWindowRequest(String commandKey, String windowKey, String channelId,
                                      String startUtc, String endUtc, String plannedVolume,
                                      String pumpStationKey) {
    }

    /** 供水窗口视图；activeCurtailmentVolume 为 null 表示无生效限供。 */
    public record WindowResponse(long id, String windowKey, String channelId, String pumpStationKey,
                                 String startUtc, String endUtc,
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
                                     String createdUtc, String updatedUtc, List<CurveNode> curve,
                                     Long stationVersion) {
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

    /** 流量曲线节点：timeUtc 为 UTC 整分钟 ISO-8601 字符串，flow 单位立方米每分钟，非负、最多 3 位小数。 */
    public record CurveNode(String timeUtc, String flow) {
    }

    /** 带流量曲线的配水申请命令；curve 为 2 至 20 个节点，首末等于窗口边界，时刻递增。 */
    public record CurveAllocationRequest(String requestId, String allocationKey, Long windowId, String userId,
                                         String amount, List<CurveNode> curve) {
    }

    /** 泵站配置写入命令；expectedVersion 为 0 表示新建泵站，否则须等于当前版本。 */
    public record StationConfigRequest(String requestId, String maxFlow, String maxRampPerMinute,
                                       Long expectedVersion) {
    }

    /** 泵站配置视图；version 随每次成功调整递增。 */
    public record StationResponse(String stationKey, String maxFlow, String maxRampPerMinute, long version,
                                  String createdUtc, String updatedUtc) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
