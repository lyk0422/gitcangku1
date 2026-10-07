package com.example.starter.water.dto;

import java.util.List;

/**
 * 灌区配水 API 请求/响应契约。水量在 API 层一律使用十进制字符串（单位立方米，最多 3 位小数），
 * 时间使用 ISO-8601 UTC 字符串；流量与渠段容量为正整数（单位立方米每秒）。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 渠网图规格：节点全集（最多 10 个）与带容量有向渠段（最多 20 条）。 */
    public record GraphSpec(List<String> nodes, List<EdgeSpec> edges) {
    }

    /** 有向渠段：from -> to，容量单位立方米每秒，正整数。 */
    public record EdgeSpec(String from, String to, Long capacity) {
    }

    /** 创建供水窗口命令；graph 非空时登记渠网，窗口成为渠网门禁窗口。 */
    public record CreateWindowRequest(String commandKey, String windowKey, String channelId,
                                      String startUtc, String endUtc, String plannedVolume,
                                      GraphSpec graph) {
    }

    /** 供水窗口视图；activeCurtailmentVolume 为 null 表示无生效限供，graphVersion 为 null 表示普通窗口。 */
    public record WindowResponse(long id, String windowKey, String channelId, String startUtc, String endUtc,
                                 String plannedVolume, String activeCurtailmentVolume, String availableTotal,
                                 Integer graphVersion, String createdUtc) {
    }

    /**
     * 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。
     * 渠网窗口必须携带 path（有序节点名，2 至 9 个，相邻节点间必须存在登记渠段）与 flow（正整数流量）；
     * 普通窗口不得携带。
     */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, List<String> path, Long flow) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，取消时归零；持有额度恰为零的已转出申请仍为 APPROVED）。
     * flow 与 path 仅渠网窗口申请非空；path 为有序节点名，顺序不可规范化为无序集合，取消后仍保留。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
                                     Long flow, List<String> path,
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

    /**
     * 渠网图修订命令；操作人由 X-Actor-Id 请求头提供（非空）。
     * requestId 为全局幂等键；expectedGraphVersion 必须等于当前图版本，否则 409。
     */
    public record GraphRevisionRequest(String requestId, Long expectedGraphVersion, GraphSpec graph) {
    }

    /** 渠网图视图：当前版本、节点全集与当前有效渠段全集（按版本冻结，历史不因取消改变）。 */
    public record GraphResponse(long windowId, int graphVersion, List<String> nodes, List<EdgeSpec> edges) {
    }

    /** 窗口历史明细：窗口本身 + 全部申请 + 全部限供记录；graph 仅渠网窗口非空。 */
    public record HistoryResponse(WindowResponse window, List<AllocationResponse> allocations,
                                  List<CurtailmentResponse> curtailments, GraphResponse graph) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
