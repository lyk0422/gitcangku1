package com.example.starter.calibration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 创建接收任务请求。
 *
 * @param taskCode 任务编号，全局唯一
 * @param antennaCode 占用天线编号
 * @param startTime 任务开始时刻，UTC
 * @param endTime 任务结束时刻，UTC，必须晚于开始时刻
 */
public record CreateTaskRequest(
        @NotBlank String taskCode,
        @NotBlank String antennaCode,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}