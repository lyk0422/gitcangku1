package com.example.starter.maintenance.service;

import java.util.List;

import com.example.starter.maintenance.api.dto.WorkOrderReadingItem;

/**
 * workOrderKey 幂等指纹计算：含工单版本、设备、基线、窗口与读数摘要。
 * 事务服务与外观补偿重放共用同一构造，保证同键异参判定一致。
 */
final class WorkOrderFingerprints {

    private WorkOrderFingerprints() {
    }

    static String create(String equipmentId, String workOrderId, long expectedVersion,
                         String baselineReadingId, String windowStart, String windowEnd) {
        return "CREATE|eq=" + equipmentId + "|wo=" + workOrderId + "|ev=" + expectedVersion
                + "|base=" + baselineReadingId + "|win=" + windowStart + "/" + windowEnd;
    }

    static String lifecycle(String operation, String equipmentId, String workOrderId,
                            long expectedVersion, long workOrderVersion) {
        return operation + "|eq=" + equipmentId + "|wo=" + workOrderId
                + "|ev=" + expectedVersion + "|wov=" + workOrderVersion;
    }

    static String terminate(String equipmentId, String workOrderId, long expectedVersion,
                            long workOrderVersion, String reason) {
        return lifecycle("TERMINATE_WORK_ORDER", equipmentId, workOrderId,
                expectedVersion, workOrderVersion) + "|reason=" + reason;
    }

    /** 读数登记摘要：工单版本 + 每条读数（标识、时刻、工时），顺序即提交顺序。 */
    static String readings(String equipmentId, String workOrderId, long expectedVersion,
                           long workOrderVersion, List<WorkOrderReadingItem> items) {
        StringBuilder sb = new StringBuilder("REGISTER_READINGS|eq=").append(equipmentId)
                .append("|wo=").append(workOrderId)
                .append("|ev=").append(expectedVersion)
                .append("|wov=").append(workOrderVersion)
                .append("|n=").append(items.size());
        for (WorkOrderReadingItem item : items) {
            sb.append("|").append(item.readingId())
                    .append("@").append(item.sampledAt())
                    .append("=").append(item.cumulativeMinutes());
        }
        return sb.toString();
    }
}
