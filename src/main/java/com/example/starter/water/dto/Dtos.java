package com.example.starter.water.dto;

import java.util.List;

/**
 * 灌区配水 API 请求/响应契约。水量在 API 层一律使用十进制字符串（单位立方米）：
 * 配额水量最多 3 位小数；下泄曲线、逐小时预留与核销水量最多 2 位小数。
 * 时间使用 ISO-8601 UTC 字符串，小时区间一律左闭右开 [hourStart, hourStart+1h)。
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
     * 窗口配置下泄曲线时 usageStartUtc/usageEndUtc 必填且对齐 UTC 整点并落在窗口内（左闭右开），
     * amount 最多 2 位小数；未配置曲线时两者必须为 null。
     */
    public record SubmitAllocationRequest(String commandKey, String allocationKey, Long windowId,
                                          String userId, String amount,
                                          String usageStartUtc, String usageEndUtc) {
    }

    /**
     * 配水申请视图。状态：REQUESTED / APPROVED / CANCELLED。
     * amount 为不可改写的原申请水量；heldAmount 为当前持有额度（REQUESTED 为 0，普通批准时等于原水量，
     * 转出时等额扣减，取消时归零；持有额度恰为零的已转出申请仍为 APPROVED）。
     * usageStartUtc/usageEndUtc 为申请覆盖的下泄小时区间（左闭右开），无曲线窗口为 null 且永不为空后改写；
     * approvalCurveVersion 为批准时固化的曲线版本号，未批准或无曲线窗口批准为 null，后续曲线调整不回改。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String requester, String status,
                                     String createdUtc, String updatedUtc,
                                     String usageStartUtc, String usageEndUtc, Integer approvalCurveVersion) {
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

    /** 下泄曲线单个小时点：hourStartUtc 为小时起点（含），capVolume 为该小时非负上限（2 位小数）。 */
    public record DrawdownPointRequest(String hourStartUtc, String capVolume) {
    }

    /** 全量替换窗口下泄曲线命令；点序列必须恰好覆盖窗口全部小时，不得重复、缺失或跨窗口。 */
    public record DrawdownRequest(String commandKey, List<DrawdownPointRequest> points) {
    }

    /** 下泄曲线小时点视图。 */
    public record DrawdownPointResponse(String hourStartUtc, String capVolume) {
    }

    /** 下泄曲线版本视图；版本创建后不可变，状态 ACTIVE/SUPERSEDED。 */
    public record DrawdownCurveResponse(long id, long windowId, int version, String status, String createdUtc,
                                        List<DrawdownPointResponse> points) {
    }

    /** 批量批准命令：按列表顺序逐笔校验，任一失败整批回滚。 */
    public record AllocationBatchRequest(String commandKey, List<String> allocationKeys) {
    }

    /** 批量批准结果：成功时含全部被批准申请（顺序与请求一致）。 */
    public record AllocationBatchResponse(List<AllocationResponse> allocations) {
    }

    /** 批量转让中的单笔：transferKey 全局唯一，源/目标必须同窗口。 */
    public record TransferBatchItem(String transferKey, String sourceAllocationKey, String targetAllocationKey) {
    }

    /** 批量转让命令：多笔转让按完整最终额度与逐小时预留一次性校验提交。 */
    public record TransferBatchRequest(String commandKey, List<TransferBatchItem> transfers) {
    }

    /** 批量转让结果：成功时含全部不可变转让流水（顺序与请求一致）。 */
    public record TransferBatchResponse(List<TransferResponse> transfers) {
    }

    /**
     * 实际核销命令；操作人由 X-Actor-Id 提供。hourStartUtc 为实际下泄发生小时（含），
     * 只扣减该申请该小时的剩余预留，volume 大于 0 且最多 2 位小数。
     */
    public record WriteoffRequest(String commandKey, String writeoffKey, String allocationKey,
                                  String hourStartUtc, String volume) {
    }

    /** 核销流水视图，创建后不可变。 */
    public record WriteoffResponse(String writeoffKey, String allocationKey, long windowId,
                                   String hourStartUtc, String volume, String actor, String createdUtc) {
    }

    /** 单条申请固化的逐小时预留快照及释放/核销计数；reservedVolume/curveVersion 不随后续操作改写。 */
    public record HourReservationResponse(String hourStartUtc, String reservedVolume, String releasedVolume,
                                          String writtenOffVolume, int curveVersion) {
    }

    /** 单个申请的逐小时预留分组（明细/历史查询用，只读）。 */
    public record AllocationReservationGroup(String allocationKey, Integer approvalCurveVersion,
                                             List<HourReservationResponse> reservations) {
    }

    /**
     * 单个小时的下泄诊断：capVolume 为当前曲线上限；reservedTotal 为固化预留合计；
     * releasedTotal 为取消累计释放；writtenOffTotal 为累计核销；
     * committedVolume 为当前实际占用（= 已核销 + 已批准未核销 = reservedTotal - releasedTotal，核销不释放容量）；
     * remaining 为该小时尚可新增预留的余量（capVolume - committedVolume）。
     */
    public record HourUsageResponse(String hourStartUtc, String capVolume, String reservedTotal,
                                    String releasedTotal, String writtenOffTotal, String committedVolume,
                                    String remaining) {
    }

    /** 窗口逐小时下泄诊断视图（只读），版本为当前 ACTIVE 曲线；无曲线时 curveVersion 为 null。 */
    public record DrawdownUsageResponse(long windowId, Integer curveVersion, List<HourUsageResponse> hours) {
    }

    /** 窗口历史明细：窗口 + 全部申请 + 全部限供 + 全部曲线版本 + 逐小时预留快照 + 全部核销流水。 */
    public record HistoryResponse(WindowResponse window, List<AllocationResponse> allocations,
                                  List<CurtailmentResponse> curtailments,
                                  List<DrawdownCurveResponse> drawdownCurves,
                                  List<AllocationReservationGroup> reservations,
                                  List<WriteoffResponse> writeoffs) {
    }

    /** 统一错误响应体；details 为可区分的结构化明细（如逐小时超限列表），无明细时为 null。 */
    public record ErrorResponse(String code, String message, Object details) {
    }
}
