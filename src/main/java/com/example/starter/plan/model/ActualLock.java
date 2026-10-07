package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 区段当前实际锁（某占用处于 INSIDE）。
 *
 * @param scheduleKey 持有锁的计划业务键
 * @param seq         计划内占用序号
 * @param trainNo     列车编号
 * @param sectionId   区段 ID
 * @param enteredAt   实际进入时刻（服务端 UTC）
 */
public record ActualLock(String scheduleKey, int seq, String trainNo, String sectionId,
                         Instant enteredAt) {
}
