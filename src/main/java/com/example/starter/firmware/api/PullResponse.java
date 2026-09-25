package com.example.starter.firmware.api;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 设备拉取响应：task 为命中或已存在的任务；result 标识本次拉取结果。
 * DISPATCHED 已下发（含返回已有任务）；NONE 无匹配投放；THROTTLED 区域受限未下发。
 * THROTTLED 为瞬态结果：不下发任务、不计入失败率样本、不改变设备或任务状态，且不占用幂等键。
 */
public record PullResponse(TaskView task, String result) {

    public static final String RESULT_DISPATCHED = "DISPATCHED";
    public static final String RESULT_NONE = "NONE";
    public static final String RESULT_THROTTLED = "THROTTLED";

    public static PullResponse dispatched(TaskView task) {
        return new PullResponse(task, RESULT_DISPATCHED);
    }

    public static PullResponse none() {
        return new PullResponse(null, RESULT_NONE);
    }

    public static PullResponse throttled() {
        return new PullResponse(null, RESULT_THROTTLED);
    }

    @JsonIgnore
    public boolean isThrottled() {
        return RESULT_THROTTLED.equals(result);
    }
}
