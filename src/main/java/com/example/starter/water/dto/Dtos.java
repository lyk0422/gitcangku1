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
     * 转出时等额扣减，执行时等额扣减，取消时归零；持有额度恰为零的申请仍为 APPROVED）；
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
     * 创建双池供水窗口命令：在普通窗口字段之上登记上下池。
     * 面积为固定正整数（平方米）；初始体积与体积上下界为十进制字符串（立方米，最多 3 位小数，非负）；
     * 水位（米）= 体积 / 面积。expectedVersion 可空，仅参与幂等身份。
     */
    public record CreateBasinWindowRequest(String commandKey, String windowKey, String channelId,
                                           String startUtc, String endUtc, String plannedVolume,
                                           Long upperAreaM2, Long lowerAreaM2,
                                           String upperInitialVolumeM3, String lowerInitialVolumeM3,
                                           String upperMinVolumeM3, String upperMaxVolumeM3,
                                           String lowerMinVolumeM3, String lowerMaxVolumeM3,
                                           String expectedVersion) {
    }

    /**
     * 双池视图。体积单位立方米，面积单位平方米；水位为展示值（体积/面积，最多 15 位小数舍入），
     * 舍入不参与水位门禁，门禁使用体积与面积的精确算术。
     */
    public record BasinPairResponse(long windowId, long upperAreaM2, long lowerAreaM2,
                                    String upperVolumeM3, String lowerVolumeM3,
                                    String upperMinVolumeM3, String upperMaxVolumeM3,
                                    String lowerMinVolumeM3, String lowerMaxVolumeM3,
                                    String upperLevelM, String lowerLevelM, String levelDiffM,
                                    String updatedUtc) {
    }

    /** 双池窗口创建视图：窗口本身 + 双池配置与当前体积。 */
    public record BasinWindowResponse(WindowResponse window, BasinPairResponse basins) {
    }

    /** 闸门执行命令；volume 为正整数字符串（立方米），expectedVersion 可空，仅参与幂等身份。 */
    public record ExecuteGateRequest(String commandKey, String executionKey, String volume,
                                     String expectedVersion) {
    }

    /**
     * 闸门执行记录视图，创建后不可变。冻结双池前后体积（立方米）与水位展示值（米）；
     * executedTotal 为申请累计已执行水量，remainingExecutable 为剩余可执行量（APPROVED 时等于持有额度）。
     */
    public record ExecutionResponse(String executionKey, String allocationKey, long windowId, String volume,
                                    String upperBeforeM3, String upperAfterM3,
                                    String lowerBeforeM3, String lowerAfterM3,
                                    String upperLevelBeforeM, String upperLevelAfterM,
                                    String lowerLevelBeforeM, String lowerLevelAfterM,
                                    String executedTotal, String remainingExecutable,
                                    String actor, String createdUtc) {
    }

    /** 申请执行明细列表视图：累计已执行水量、剩余可执行量与全部执行记录（按发生顺序）。 */
    public record ExecutionListResponse(String allocationKey, long windowId, String executedTotal,
                                        String remainingExecutable, List<ExecutionResponse> executions) {
    }

    /** 统一错误响应体。 */
    public record ErrorResponse(String code, String message) {
    }
}
