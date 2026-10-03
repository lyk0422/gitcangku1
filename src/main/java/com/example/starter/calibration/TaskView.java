package com.example.starter.calibration;

import java.time.Instant;
import java.util.List;

/**
 * 任务查询视图，含当前绑定版本。
 *
 * @param id 任务主键
 * @param taskCode 任务编号
 * @param antennaCode 天线编号
 * @param startTime 开始时刻，UTC
 * @param endTime 结束时刻，UTC
 * @param status 任务状态
 * @param bindings 任务的绑定历史（含被替换的旧绑定）
 */
public record TaskView(
        long id,
        String taskCode,
        String antennaCode,
        Instant startTime,
        Instant endTime,
        TaskStatus status,
        List<BindingView> bindings) {
}