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
     * 转出时等额扣减，取消时保留已执行量；持有额度恰为零的已转出申请仍为 APPROVED）。
     * executedAmount 为累计已执行水量，执行成功不可取消返还。
     */
    public record AllocationResponse(String allocationKey, long windowId, String userId, String amount,
                                     String heldAmount, String executedAmount, String requester, String status,
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
     * 登记闸门窗口命令：在普通窗口字段之外登记上下池。
     * 面积为固定正整数（平方米），初始体积与体积上下界为整数（立方米），水位=体积/面积（米）。
     */
    public record CreateGateWindowRequest(String commandKey, String windowKey, String channelId,
                                          String startUtc, String endUtc, String plannedVolume,
                                          Long upperAreaM2, Long upperInitialVolumeM3,
                                          Long upperMinVolumeM3, Long upperMaxVolumeM3,
                                          Long lowerAreaM2, Long lowerInitialVolumeM3,
                                          Long lowerMinVolumeM3, Long lowerMaxVolumeM3) {
    }

    /**
     * 双池状态视图。体积单位立方米，面积单位平方米，水位单位米（显示舍入值，不参与水位门禁）；
     * version 为双池状态版本号，每次成功执行加 1。
     */
    public record BasinResponse(long windowId, long upperAreaM2, long upperVolumeM3,
                                long upperMinVolumeM3, long upperMaxVolumeM3, String upperLevelM,
                                long lowerAreaM2, long lowerVolumeM3,
                                long lowerMinVolumeM3, long lowerMaxVolumeM3, String lowerLevelM,
                                long version, String updatedUtc) {
    }

    /** 闸门窗口视图：窗口字段 + 双池状态。 */
    public record GateWindowResponse(long id, String windowKey, String channelId, String startUtc, String endUtc,
                                     String plannedVolume, String createdUtc, BasinResponse basin) {
    }

    /** 闸门执行命令；volume 为正整数字符串（立方米），expectedVersion 为期望的双池版本号。 */
    public record ExecuteRequest(String commandKey, String executionKey, String volume, Long expectedVersion) {
    }

    /**
     * 执行明细视图：冻结执行前后双池体积（立方米）与水位（米，显示舍入值）。
     * version 为执行后的双池版本号；remainingExecutable 为该申请剩余可执行量（持有额度 - 累计已执行）。
     */
    public record ExecutionResponse(String executionKey, String allocationKey, long windowId, long volumeM3,
                                    long upperVolumeBeforeM3, long upperVolumeAfterM3,
                                    String upperLevelBeforeM, String upperLevelAfterM,
                                    long lowerVolumeBeforeM3, long lowerVolumeAfterM3,
                                    String lowerLevelBeforeM, String lowerLevelAfterM,
                                    long version, String remainingExecutable, String actor, String createdUtc) {
    }

    /** 申请执行明细列表视图：含累计已执行量与剩余可执行量。 */
    public record ExecutionListResponse(String allocationKey, long windowId, String status,
                                        String executedTotal, String remainingExecutable,
                                        List<ExecutionResponse> executions) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
