package com.example.starter.firmware.api;

/**
 * 设备拉取响应：普通发布命中时 task 非空；双分区发布命中时 bootTask 非空；无匹配投放时两者均为 null。
 */
public record PullResponse(TaskView task, BootTaskView bootTask) {

    /**
     * 普通发布任务拉取结果。
     */
    public PullResponse(TaskView task) {
        this(task, null);
    }
}
