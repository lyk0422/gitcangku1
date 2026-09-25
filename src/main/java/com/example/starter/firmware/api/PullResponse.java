package com.example.starter.firmware.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 设备拉取响应。
 *
 * @param task   命中或已存在的任务；EMPTY 与 INCOMPATIBLE 时为 null（JSON 中省略）
 * @param result TASK 命中/已有任务；EMPTY 通过既有门禁但无投放；
 *               INCOMPATIBLE 目标固件不兼容设备硬件型号（未创建任务、未计失败率样本、未改设备状态）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PullResponse(TaskView task, String result) {

    public static PullResponse task(TaskView view) {
        return new PullResponse(view, "TASK");
    }

    public static PullResponse empty() {
        return new PullResponse(null, "EMPTY");
    }

    public static PullResponse incompatible() {
        return new PullResponse(null, "INCOMPATIBLE");
    }
}
