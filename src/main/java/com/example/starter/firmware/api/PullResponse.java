package com.example.starter.firmware.api;

/**
 * 设备拉取响应。
 *
 * @param task                命中或已存在的任务；未下发任务时为 null
 * @param result              拉取结果：TASK 已下发或已有任务；DEFERRED 维护窗口外顺延；
 *                            RELEASE_PAUSED 发布单已暂停（窗口内）；NONE 无匹配投放
 * @param nextWindowStartUtc  仅 DEFERRED 时携带：下一次维护窗口开始的 UTC 时刻，ISO-8601
 */
public record PullResponse(TaskView task, String result, String nextWindowStartUtc) {

    public static PullResponse ofTask(TaskView task) {
        return new PullResponse(task, "TASK", null);
    }

    public static PullResponse none() {
        return new PullResponse(null, "NONE", null);
    }

    public static PullResponse deferred(String nextWindowStartUtc) {
        return new PullResponse(null, "DEFERRED", nextWindowStartUtc);
    }

    public static PullResponse releasePaused() {
        return new PullResponse(null, "RELEASE_PAUSED", null);
    }
}
