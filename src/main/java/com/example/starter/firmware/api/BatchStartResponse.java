package com.example.starter.firmware.api;

import java.util.List;

/**
 * 批量启动响应：为全部通过校验的设备创建（或复用已有）的任务。
 */
public record BatchStartResponse(long releaseId, List<TaskView> tasks) {
}
