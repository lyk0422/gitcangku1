package com.example.starter.firmware.api;

/**
 * 设备拉取响应：result 为 TASK（命中或已存在任务）、NONE（无匹配投放）、
 * PATH_BLOCKED（前置链存在未安装中间版本，nextVersion 为下一个必须安装的版本，task 为 null）。
 */
public record PullResponse(TaskView task, String result, String nextVersion) {

    public static final String RESULT_TASK = "TASK";
    public static final String RESULT_NONE = "NONE";
    public static final String RESULT_PATH_BLOCKED = "PATH_BLOCKED";

    public static PullResponse task(TaskView task) {
        return new PullResponse(task, RESULT_TASK, null);
    }

    public static PullResponse none() {
        return new PullResponse(null, RESULT_NONE, null);
    }

    public static PullResponse blocked(String nextVersion) {
        return new PullResponse(null, RESULT_PATH_BLOCKED, nextVersion);
    }
}
