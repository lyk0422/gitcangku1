package com.example.starter.firmware.api;

/**
 * 拓扑波次成员视图：设备及其本次领取创建的投放任务。
 */
public record WaveMemberView(String deviceId, long taskId) {
}
