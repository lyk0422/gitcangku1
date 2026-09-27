package com.example.starter.firmware.api;

import java.util.List;

/**
 * 回执复核列表响应：带复核标记（含已确认）的任务。
 */
public record ReviewListResponse(List<TaskView> reviews) {
}
