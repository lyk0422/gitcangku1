package com.example.starter.firmware.api;

/**
 * 设备拉取响应：命中普通发布时 task 为普通任务；命中双分区发布时 dualBootTask 为双分区任务；
 * 两者至多一个非 null；无匹配投放时均为 null。
 */
public record PullResponse(TaskView task, DualBootTaskView dualBootTask) {

    /**
     * 普通发布拉取结果。
     */
    public static PullResponse ofNormal(TaskView task) {
        return new PullResponse(task, null);
    }

    /**
     * 双分区发布拉取结果。
     */
    public static PullResponse ofDualBoot(DualBootTaskView task) {
        return new PullResponse(null, task);
    }

    /**
     * 无匹配投放。
     */
    public static PullResponse empty() {
        return new PullResponse(null, null);
    }
}