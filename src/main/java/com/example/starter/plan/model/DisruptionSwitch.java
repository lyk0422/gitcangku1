package com.example.starter.plan.model;

/**
 * 区段封锁切换单主记录。
 *
 * @param id                 主键
 * @param switchKey          切换单业务键，全局唯一
 * @param sectionId          被封锁区段 ID
 * @param windowStartUtc     封锁窗口开始时刻（含），UTC 毫秒
 * @param windowEndUtc       封锁窗口结束时刻（不含），UTC 毫秒
 * @param status             切换单状态
 * @param activatedRequestId 成功激活的请求 ID，未激活时为 null
 */
public record DisruptionSwitch(long id, String switchKey, String sectionId, long windowStartUtc,
                               long windowEndUtc, DisruptionStatus status, String activatedRequestId) {
}
