package com.example.starter.firmware.api;

/**
 * 设备拉取响应。
 * result：ISSUED 命中或已存在任务（task 非空）；NONE 无匹配投放（task 为 null）；
 * THROTTLED 区域达到进行中上限被限流（task 为 null，不下发任务、不改变设备或任务状态）。
 */
public record PullResponse(String result, TaskView task) {

    public static final String RESULT_ISSUED = "ISSUED";
    public static final String RESULT_NONE = "NONE";
    public static final String RESULT_THROTTLED = "THROTTLED";

    public static PullResponse issued(TaskView task) {
        return new PullResponse(RESULT_ISSUED, task);
    }

    public static PullResponse none() {
        return new PullResponse(RESULT_NONE, null);
    }

    public static PullResponse throttled() {
        return new PullResponse(RESULT_THROTTLED, null);
    }
}
