package com.example.starter.water.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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

    /** 提交配水申请命令；申请人由 X-Actor-Id 请求头提供。hourlyAmounts 为可选逐小时预留分布。 */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount, List<HourAmountRequest> hourlyAmounts) {
    }

    /** 逐小时水量项：hourStartUtc 必须整点对齐（区间左闭右开），amount 为十进制字符串。 */
    public record HourAmountRequest(String hourStartUtc, String amount) {
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

    /** 下泄曲线点命令项：hourStartUtc 整点对齐，limit 非负且最多 2 位小数。 */
    public record CurvePointRequest(String hourStartUtc, String limit) {
    }

    /** 全量替换下泄曲线命令：points 必须恰好覆盖窗口全部小时，不得重复、缺失或跨窗口。 */
    public record PutCurveRequest(String commandKey, List<CurvePointRequest> points) {
    }

    /** 下泄曲线点视图。 */
    public record CurvePointResponse(String hourStartUtc, String limit) {
    }

    /** 下泄曲线版本视图。 */
    public record CurveResponse(long windowId, int version, List<CurvePointResponse> points, String createdUtc) {
    }

    /** 下泄曲线全部历史版本视图，按版本号升序。 */
    public record CurveVersionsResponse(long windowId, List<CurveResponse> versions) {
    }

    /** 窗口逐小时诊断视图；limit/remaining 在窗口未配置曲线时为 null。 */
    public record HourDiagnostics(String hourStartUtc, String limit, String reserved, String redeemed,
                                  String remaining) {
    }

    /** 窗口逐小时诊断列表视图，覆盖窗口全部小时，按小时升序。 */
    public record WindowHoursResponse(long windowId, List<HourDiagnostics> hours) {
    }

    /** 核销命令：按发生小时扣减同一小时额度；操作人由 X-Actor-Id 请求头提供。 */
    public record RedeemRequest(String commandKey, String hourStartUtc, String amount) {
    }

    /** 核销流水视图，创建后不可变。 */
    public record RedemptionResponse(long id, String allocationKey, String hourStartUtc, String amount,
                                     String actor, String createdUtc) {
    }

    /** 申请逐小时状态视图：reserved 为当前预留，redeemed 为已核销，remaining 为二者差额。 */
    public record AllocationHourView(String hourStartUtc, String reserved, String redeemed, String remaining) {
    }

    /** 批准快照逐小时预留项。 */
    public record SnapshotHourView(String hourStartUtc, String amount) {
    }

    /** 批准快照视图：curveVersion 为批准时固化的曲线版本，无曲线时为 null；未批准时整个快照为 null。 */
    public record ApprovalSnapshotView(Integer curveVersion, List<SnapshotHourView> hours, String createdUtc) {
    }

    /** 申请明细视图：申请本体 + 逐小时状态 + 批准快照 + 核销流水。 */
    public record AllocationDetailResponse(AllocationResponse allocation, List<AllocationHourView> hours,
                                           ApprovalSnapshotView approvalSnapshot,
                                           List<RedemptionResponse> redemptions) {
    }

    /** 批量批准命令：任一失败整次回滚。 */
    public record BatchApproveRequest(String commandKey, List<String> allocationKeys) {
    }

    /** 批量批准结果视图，按请求顺序返回。 */
    public record BatchApproveResponse(List<AllocationResponse> allocations) {
    }

    /** 小时超限明细：limit 为小时上限，required 为累计要求值，deficit 为差额（required - limit）。 */
    public record HourViolation(String hourStartUtc, String limit, String required, String deficit) {
    }

    /** 统一错误响应体；violations 仅在逐小时校验失败（422）时出现，其余为 null 不序列化。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String code, String message, List<HourViolation> violations) {
        public ErrorResponse(String code, String message) {
            this(code, message, null);
        }
    }
}
