package com.example.starter.firmware.api;

import java.util.List;

/**
 * 双分区试启动任务列表响应。
 */
public record BootTaskListResponse(List<BootTaskView> tasks) {
}
