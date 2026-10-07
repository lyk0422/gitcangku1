package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 实际进出轨迹事件，追加后不可变；计划取消不删除。
 *
 * @param id          主键
 * @param planId      所属计划 id
 * @param occupancyId 占用 id
 * @param seq         占用在计划内的序号
 * @param eventType   事件类型：ENTER 进入 / EXIT 退出
 * @param serverUtc   服务端确认时刻（UTC），由服务器时钟产生，调用方不可指定
 * @param planVersion 事件发生时原计划版本
 * @param operator    操作者，非空
 * @param requestId   全局幂等请求 id
 * @param createdAt   记录创建时刻，UTC 毫秒
 */
public record ActualEvent(long id, long planId, long occupancyId, int seq, String eventType,
                          Instant serverUtc, int planVersion, String operator, String requestId,
                          long createdAt) {
}
