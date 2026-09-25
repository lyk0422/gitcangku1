package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 写接口请求体集合。commandKey 为调用方幂等键；occurredAt 为 UTC 时间。
 */
public final class Requests {

    private Requests() {
    }

    /** 事件上报请求。 */
    public record ReportRequest(String incidentKey, String severity, String summary, String reporter) {
    }

    /** 接管请求。 */
    public record TakeoverRequest(String commandKey) {
    }

    /** 交接发起请求：toCommander 必须不同于当前指挥人。 */
    public record TransferRequest(String commandKey, String toCommander) {
    }

    /** 交接接受请求，操作人由 X-Actor-Id 指定且须为待接受目标人。 */
    public record TransferAcceptRequest(String commandKey) {
    }

    /** 处置记录追加请求。 */
    public record ActionRequest(String commandKey, String actionKey, String actionType,
                                String note, Instant occurredAt) {
    }

    /** 状态变更请求：targetStatus 只允许 CONTAINED / RESOLVED / CLOSED。 */
    public record StatusRequest(String commandKey, String targetStatus) {
    }

    /** 遏制逾期检查请求：以注入 Clock 的当前时刻评估，不做定时扫描。 */
    public record EscalationCheckRequest(String commandKey) {
    }

    /** 升级确认请求：note 为非空处置说明，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record EscalationAckRequest(String commandKey, String note) {
    }

    /**
     * 处置任务创建请求：taskKey 事件内唯一；groupCode、title 非空；
     * blockerIncidentKeys 为 0~5 个阻塞事件键，必须存在且不能是自身，重复键按去重处理。
     * highRisk 为高危标记（缺省 false）；workGrids 为作业网格集合（高危任务必填，规范化排序）；
     * finalPosition 为最终位置网格码（批量派工前必须就位，可缺省后补）。
     */
    public record TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                    String title, List<String> blockerIncidentKeys,
                                    Boolean highRisk, List<String> workGrids, String finalPosition) {

        /** 兼容构造：非高危、无作业网格、无最终位置。 */
        public TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                 String title, List<String> blockerIncidentKeys) {
            this(commandKey, taskKey, groupCode, title, blockerIncidentKeys, null, null, null);
        }
    }

    /** 任务完成/取消/开始/撤离登记请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /**
     * 疏散区域登记请求：grids 为简化网格集合（规范化排序）；effectiveFrom/effectiveTo 为
     * UTC 左闭右开生效窗口；riskLevel 取值 LOW/MEDIUM/HIGH。zoneKey 由服务端按指纹生成。
     */
    public record ZoneRegisterRequest(String commandKey, List<String> grids,
                                      Instant effectiveFrom, Instant effectiveTo, String riskLevel) {
    }

    /** 疏散区域修订请求：仅可修订网格与窗口，等级沿用谱系；产生新版本并使旧版本豁免失效。 */
    public record ZoneReviseRequest(String commandKey, List<String> grids,
                                    Instant effectiveFrom, Instant effectiveTo) {
    }

    /** 撤离豁免签发请求：针对（任务，区域当前版本），允许任务创建前预授权。 */
    public record ExemptionGrantRequest(String commandKey, String taskKey, String zoneKey,
                                        String reason) {
    }

    /** 批量派工单项：finalPosition 可覆盖任务最终位置；resources 为所需资源键集合。 */
    public record DispatchItem(String taskKey, String finalPosition, List<String> resources) {
    }

    /**
     * 批量派工请求：先校验全部任务的最终位置、资源依赖与撤离豁免，
     * 任一缺失 422 且租约与任务状态全部回滚；全部通过后原子置 IN_PROGRESS 并获取租约。
     */
    public record DispatchRequest(String commandKey, List<DispatchItem> items) {
    }
}
