package com.example.starter.plan.model;

/**
 * 实际进出轨迹事件，追加后不可变。
 *
 * @param id          主键
 * @param planId      所属计划 id
 * @param occupancyId 占用 id
 * @param seq         计划内占用序号
 * @param eventType   事件类型：ENTER / EXIT
 * @param serverUtc   事件生效的服务端 UTC 毫秒时刻
 * @param planVersion 事件发生时原计划版本
 * @param operator    操作者
 * @param requestId   全局幂等请求 id
 */
public record ActualEvent(long id, long planId, long occupancyId, int seq, String eventType,
                          long serverUtc, int planVersion, String operator, String requestId) {
}
