package com.example.starter.firmware.api;

import java.util.List;

/**
 * 任务明细查询响应。
 */
public record TaskListResponse(List<TaskView> tasks) {
}
