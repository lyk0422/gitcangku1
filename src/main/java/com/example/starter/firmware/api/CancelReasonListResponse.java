package com.example.starter.firmware.api;

import java.util.List;

/**
 * 任务取消原因列表响应。
 */
public record CancelReasonListResponse(List<TaskCancelReasonView> reasons) {
}
