package com.example.starter.work.model;

import java.time.Instant;

/**
 * 施工单取消记录，追加后不可变；一个施工单最多一条。
 *
 * @param id          主键
 * @param workKey     被取消的施工单业务键
 * @param version     取消时施工单版本
 * @param operator    取消操作者
 * @param cancelledAt 取消时刻，UTC
 */
public record WorkCancelRecord(long id, String workKey, int version, String operator,
                               Instant cancelledAt) {
}
