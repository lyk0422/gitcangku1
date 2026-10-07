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
     */
    public record TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                    String title, List<String> blockerIncidentKeys) {
    }

    /** 任务完成/取消请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /**
     * 启用双人监护请求：两个固定合成操作者必须非空且互不相同；启用后不可更换。
     */
    public record GuardianEnableRequest(String commandKey, String guardianOne, String guardianTwo) {
    }

    /** 在场签到请求：操作人由 X-Actor-Id 指定且必须为固定监护人本人。 */
    public record AttendanceSignRequest(String commandKey) {
    }

    /**
     * 撤销在场凭证请求：guardian 为被撤销的固定监护人；操作人由 X-Actor-Id 指定
     * 且必须为当前指挥人。
     */
    public record AttendanceRevokeRequest(String commandKey, String guardian) {
    }

    /**
     * 完成时引用的单方当前凭证：guardian 为固定监护人，generation 为其当前凭证代次。
     */
    public record AttendanceRef(String guardian, Integer generation) {
    }

    /**
     * 双人监护任务完成请求：expectedVersion 为监护配置期望版本（当前固定为 1），
     * attendances 须显式引用双方当前代次；非监护任务两者均留空。
     */
    public record TaskCompleteRequest(String commandKey, Integer expectedVersion,
                                      List<AttendanceRef> attendances) {
    }
}
