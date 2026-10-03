package com.example.starter.calibration;

import java.time.Instant;

/**
 * 接收任务。
 *
 * @param id 主键
 * @param taskCode 任务编号，全局唯一
 * @param antennaId 占用天线主键
 * @param startTime 任务开始时刻，UTC
 * @param endTime 任务结束时刻，UTC，晚于开始时刻
 * @param status 任务状态
 */
public record ReceiveTask(
        Long id,
        String taskCode,
        long antennaId,
        Instant startTime,
        Instant endTime,
        TaskStatus status) {
}