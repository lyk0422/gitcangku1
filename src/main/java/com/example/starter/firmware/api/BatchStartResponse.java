package com.example.starter.firmware.api;

import java.util.List;

/**
 * 批量启动响应：本次涉及设备的任务列表（新建或已存在）。
 */
public record BatchStartResponse(List<TaskView> tasks) {
}
