package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

import java.util.List;

/**
 * 投放任务视图。任务行固化的兼容证据随任务返回；已存在任务再次拉取时展示其下发时版本，
 * 设备后续上报或矩阵修订均不改写这些字段。
 *
 * @param deviceModules 下发时设备模块清单快照（按模块名排序），无模块登记时为空列表
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion, int matrixVersion, int exemptionVersion,
                       String grantedVia, List<DeviceModulesView.Item> deviceModules) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.matrixVersion(), task.exemptionVersion(),
                task.grantedVia().name(), SnapshotJson.parseItems(task.deviceModulesJson()));
    }
}
