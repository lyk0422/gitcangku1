package com.example.starter.plan.model;

import java.math.BigDecimal;

/**
 * 列车应急制动档案。
 *
 * @param id                主键
 * @param trainNo           列车编号，全局唯一
 * @param reactionTimeS     列车反应时间 t，秒，正十进制、最多3位小数
 * @param baseDeceleration  基础减速度 a，米每平方秒，正十进制、最多3位小数
 * @param version           档案版本，从 1 开始，每次修改加一
 */
public record TrainProfile(long id, String trainNo, BigDecimal reactionTimeS,
                           BigDecimal baseDeceleration, int version) {
}
