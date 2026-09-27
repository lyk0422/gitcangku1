package com.example.starter.maintenance;

/**
 * 快照固化的维护窗口版本响应。
 *
 * @param windowId           维护窗口标识
 * @param maintenanceVersion 快照生成时固化的维护版本号
 */
public record SnapshotWindowResponse(
        String windowId,
        int maintenanceVersion) {

    /**
     * 由领域对象构造响应。
     */
    public static SnapshotWindowResponse of(SnapshotWindow window) {
        return new SnapshotWindowResponse(window.windowId(), window.maintenanceVersion());
    }
}
