package com.example.starter.maintenance;

/**
 * 合并快照固化的维护窗口摘要：记录窗口标识与其维护版本，
 * 后续窗口删除、更正均不影响已固化内容。
 *
 * @param windowId           维护窗口标识
 * @param maintenanceVersion 快照生成时该窗口的维护版本号
 */
public record SnapshotWindow(
        String windowId,
        int maintenanceVersion) {
}
