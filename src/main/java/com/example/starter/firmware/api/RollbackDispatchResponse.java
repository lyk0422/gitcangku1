package com.example.starter.firmware.api;

/**
 * 回退波次派发响应；无当前跳任务（暂停、后续跳、已到目标、被冲突占用）时 task 为 null。
 */
public record RollbackDispatchResponse(RollbackTaskView task) {
}
