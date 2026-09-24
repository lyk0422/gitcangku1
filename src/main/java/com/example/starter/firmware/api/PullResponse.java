package com.example.starter.firmware.api;

/**
 * 设备拉取响应。
 *
 * @param result             拉取结果：
 *                           DISPATCHED 命中并下发（或设备已有任务）；
 *                           NO_MATCH 无匹配投放（无未终结发布单、版本或分桶不匹配、发布单已取消）；
 *                           DEFERRED 开启维护窗口后设备当前在窗口外，任务顺延，不下发不改状态；
 *                           RELEASE_PAUSED 窗口内但发布单处于 PAUSED，不下发新任务，与顺延可区分
 * @param task               命中或已存在的任务视图；DEFERRED、NO_MATCH、RELEASE_PAUSED（新设备）时为 null
 * @param nextWindowStartUtc DEFERRED 时下一次窗口开始的 UTC 时刻（ISO-8601），其余情况为 null
 * @param reason             非 DISPATCHED 结果的机器可读补充理由，便于客户端区分
 */
public record PullResponse(String result, TaskView task, String nextWindowStartUtc, String reason) {

    public static final String RESULT_DISPATCHED = "DISPATCHED";
    public static final String RESULT_NO_MATCH = "NO_MATCH";
    public static final String RESULT_DEFERRED = "DEFERRED";
    public static final String RESULT_RELEASE_PAUSED = "RELEASE_PAUSED";

    public static PullResponse dispatched(TaskView task) {
        return new PullResponse(RESULT_DISPATCHED, task, null, null);
    }

    public static PullResponse noMatch(String reason) {
        return new PullResponse(RESULT_NO_MATCH, null, null, reason);
    }

    public static PullResponse deferred(String nextWindowStartUtc) {
        return new PullResponse(RESULT_DEFERRED, null, nextWindowStartUtc, "OUTSIDE_MAINTENANCE_WINDOW");
    }

    public static PullResponse paused() {
        return new PullResponse(RESULT_RELEASE_PAUSED, null, null, "RELEASE_PAUSED");
    }
}
