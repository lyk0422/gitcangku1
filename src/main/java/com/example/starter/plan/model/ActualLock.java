package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 区段当前实际锁（一条 INSIDE 占用），同区段至多一条。
 *
 * @param scheduleKey 持有锁的计划业务键
 * @param seq         占用在计划内的序号
 * @param trainNo     列车编号
 * @param sectionId   区段 ID
 * @param enteredAt   实际进入时刻（服务端确认），UTC
 */
public record ActualLock(String scheduleKey, int seq, String trainNo, String sectionId,
                         Instant enteredAt) {
}
