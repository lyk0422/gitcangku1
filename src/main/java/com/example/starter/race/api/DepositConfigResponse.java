package com.example.starter.race.api;

/**
 * 赛事申诉保证金配置响应。
 *
 * @param raceId        赛事ID
 * @param version       配置后的赛事版本号
 * @param depositAmount 保证金当前配置金额（人民币元，正整数）
 */
public record DepositConfigResponse(String raceId, int version, int depositAmount) {
}
