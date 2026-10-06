package com.example.starter.calibration.api.dto;

/**
 * 新增或更新仪器分辨率格点配置请求。origin/step 为最多 6 位小数的十进制字符串，step 必须为正。
 *
 * @param requestId       客户端请求 ID（幂等键）；同键同参重放首次响应，异参 409
 * @param origin          格点原点 o，十进制字符串，可为负
 * @param step            格点步长 s，正数十进制字符串
 * @param expectedVersion 期望版本；缺省表示新建（版本从 1 起），更新时必填且须等于当前版本
 */
public record UpsertResolutionRequest(
        String requestId,
        String origin,
        String step,
        Integer expectedVersion) {
}
