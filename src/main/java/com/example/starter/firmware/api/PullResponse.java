package com.example.starter.firmware.api;

/**
 * 设备拉取响应。
 *
 * @param task   命中或已存在的任务；无匹配投放或被兼容矩阵拦截时为 null
 * @param result 拉取结果：TASK 返回任务，NONE 无匹配投放，INCOMPATIBLE 目标固件不兼容设备硬件型号
 */
public record PullResponse(TaskView task, String result) {

    public static PullResponse ofTask(TaskView task) {
        return new PullResponse(task, "TASK");
    }

    public static PullResponse none() {
        return new PullResponse(null, "NONE");
    }

    public static PullResponse incompatible() {
        return new PullResponse(null, "INCOMPATIBLE");
    }
}
