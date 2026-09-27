package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

import java.util.List;

/**
 * 投放任务视图。模块兼容字段为下发时固化的证据快照，后续矩阵修订/设备重报不改写：
 * moduleSummary 为设备模块摘要（按模块名排序）；matrixVersion/exemptionVersion 为固化版本，可能为 null。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion,
                       List<ModuleEntryView> moduleSummary, Integer matrixVersion,
                       Integer exemptionVersion) {

    public static TaskView of(RolloutTask task, ReleaseOrder order, List<ModuleEntryView> moduleSummary) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), moduleSummary,
                task.matrixVersion(), task.exemptionVersion());
    }
}
