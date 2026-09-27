package com.example.starter.firmware.api;

import java.util.List;

/**
 * 批量启动结果：全体设备校验通过才创建任务；created 为实际新建的任务视图。
 * 已有任务的设备返回既有任务（不重复创建）。
 */
public record BatchStartResponse(long releaseId, List<TaskView> tasks) {
}
