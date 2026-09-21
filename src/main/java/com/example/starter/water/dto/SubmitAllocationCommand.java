package com.example.starter.water.dto;

/**
 * 提交配水申请命令。申请人由 X-Actor-Id 请求头提供，不在请求体内。
 *
 * @param commandKey    幂等命令键
 * @param allocationKey 申请业务键，全局唯一
 * @param windowId      目标窗口内部 ID
 * @param userId        用水户 ID
 * @param volume        申请水量，十进制字符串，单位立方米，最多 3 位小数且大于 0
 */
public record SubmitAllocationCommand(
        String commandKey,
        String allocationKey,
        Long windowId,
        String userId,
        String volume) {
}
