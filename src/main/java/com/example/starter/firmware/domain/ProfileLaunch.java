package com.example.starter.firmware.domain;

/**
 * 硬件画像投放单：一次按设备当次冻结画像筛选目标的发布动作。
 *
 * @param id        投放单ID
 * @param requestId 投放请求ID，全局唯一；同键重放首次目标清单，异参返回409
 * @param versionId 发布人选择的固件版本ID
 */
public record ProfileLaunch(long id, String requestId, long versionId) {
}
