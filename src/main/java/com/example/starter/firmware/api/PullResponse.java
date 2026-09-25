package com.example.starter.firmware.api;

/**
 * 设备拉取响应。
 * result：ISSUED 命中或已存在任务（task 非空）；NONE 无匹配投放；
 * PATH_BLOCKED 前置链拦截（task 为空，requiredVersion 为下一个必须安装的中间版本）。
 */
public record PullResponse(TaskView task, String result, String requiredVersion) {

    public static PullResponse issued(TaskView task) {
        return new PullResponse(task, "ISSUED", null);
    }

    public static PullResponse none() {
        return new PullResponse(null, "NONE", null);
    }

    public static PullResponse pathBlocked(String requiredVersion) {
        return new PullResponse(null, "PATH_BLOCKED", requiredVersion);
    }
}
