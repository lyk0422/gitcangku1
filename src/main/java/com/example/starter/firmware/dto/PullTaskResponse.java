package com.example.starter.firmware.dto;

/**
 * 设备拉取任务响应。
 *
 * @param task   已有或新建的任务；不满足投放条件时为 null
 * @param reason 未投放原因：ROLLOUT_NOT_ACTIVE / MODEL_MISMATCH / VERSION_MISMATCH /
 *               OUT_OF_BUCKET；已返回任务时为 null
 */
public record PullTaskResponse(TaskResponse task, String reason) {
}
