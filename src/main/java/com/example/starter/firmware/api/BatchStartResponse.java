package com.example.starter.firmware.api;

import java.util.List;

/**
 * 批量启动响应：返回本批涉及设备的任务视图（已存在任务原样返回，不重复创建）。
 */
public record BatchStartResponse(List<TaskView> tasks) {
}
