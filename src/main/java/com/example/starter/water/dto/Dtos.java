package com.example.starter.water.dto;

import java.util.List;

/**
 * 灌区配水 API 请求/响应契约。水量在 API 层一律使用十进制字符串（单位立方米，最多 3 位小数），
 * 时间使用 ISO-8601 UTC 字符串。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 创建供水窗口命令；canalGraph 非空时登记为渠网窗口（适用有向渠网门禁）。 */
    public record CreateWindowRequest(String commandKey, String windowKey, String channelId,
                                      String startUtc, String endUtc, String plannedVolume,
                                      CanalGraphRequest canalGraph) {
    }

    /** 渠网登记：最多 10 个节点、20 条带容量有向渠段；容量单位立方米每秒。 */
    public record CanalGraphRequest(List<String> nodes, List<CanalEdgeSpec> edges) {
    }

    /** 带容量有向渠段定义；capacity 为十进制字符串，单位立方米每秒，最多 3 位小数。 */
    public record CanalEdgeSpec(String from, String to, String capacity) {
    }

    /** 渠段引用（图修订删除列表用）。 */
    public record CanalEdgeRef(String from, String to) {
    }

    /** 申请路径中的一条有向渠段；路径有序，不得规范化成无序集合。 */
    public record PathSegment(String from, String to) {
    }

    /** 供水窗口视图；activeCurtailmentVolume 为 null 表示无生效限供；graphVersion 仅渠网窗口非空。 */
    public record WindowResponse(long id, String windowKey, String channelId, String startUtc, String endUtc,
                                 String plannedVolume, String activeCurtailmentVolume, String availableTotal,
                                 String createdUtc, Integer graphVersion) {
    }

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。渠网窗口必须携带 path（1 至 8 条首尾相接渠段）与 flowRate（整数，立方米每秒）。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, List<PathSegment> path,
                                          String flowRate) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，取消时归零；持有额度恰为零的已转出申请仍为 APPROVED）。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
                                     String createdUtc, String updatedUtc, List<PathSegment> path,
                                     String flowRate) {
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

    /** 渠网图修订命令；操作者由 X-Actor-Id 请求头提供（非空）。expectedGraphVersion 为期望图版本。 */
    public record ReviseCanalGraphRequest(String requestId, Long expectedGraphVersion,
                                          List<CanalEdgeSpec> updates, List<CanalEdgeSpec> add,
                                          List<CanalEdgeRef> remove) {
    }

    /** 渠段视图；approvedFlow 为当前已批准流量贡献之和（整数，立方米每秒）。 */
    public record CanalEdgeView(String from, String to, String capacity, String approvedFlow) {
    }

    /** 渠网图视图：冻结的节点全集、当前边全集与图版本；取消申请不改变图与历史。 */
    public record CanalGraphResponse(long windowId, int version, List<String> nodes,
                                     List<CanalEdgeView> edges) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
