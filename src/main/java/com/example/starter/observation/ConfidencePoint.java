package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 置信度轨迹点：按观测版本与时间排序，描述置信度的确定与变化。
 *
 * @param version    该轨迹点对应的观测版本号
 * @param confidence 该轨迹点之后该版本的置信度（0-100）
 * @param eventType  事件类型：INITIAL 初始 / CONFIRMED 复核扣减 / VERSION 新版本固化继承值
 * @param flagKey    触发事件的质量标记标识；INITIAL/VERSION 事件不返回
 * @param category   触发事件的质量问题类别；INITIAL/VERSION 事件不返回
 * @param delta      本次扣减幅度（非正数）；INITIAL/VERSION 事件不返回
 * @param occurredAt 事件发生时间（服务器时区）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfidencePoint(
        int version,
        int confidence,
        ConfidenceEventType eventType,
        String flagKey,
        QualityCategory category,
        Integer delta,
        LocalDateTime occurredAt) {
}
