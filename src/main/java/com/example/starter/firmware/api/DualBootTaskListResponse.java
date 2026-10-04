package com.example.starter.firmware.api;

import java.util.List;

/**
 * 双分区任务明细查询响应。
 */
public record DualBootTaskListResponse(List<DualBootTaskView> tasks) {
}