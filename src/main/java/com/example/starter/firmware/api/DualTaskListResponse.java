package com.example.starter.firmware.api;

import java.util.List;

/**
 * 双分区任务明细列表响应。
 */
public record DualTaskListResponse(List<DualTaskView> tasks) {
}
