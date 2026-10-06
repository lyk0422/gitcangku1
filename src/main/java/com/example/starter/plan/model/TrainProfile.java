package com.example.starter.plan.model;

import java.math.BigDecimal;

/**
 * 列车应急制动档案。
 *
 * @param id          主键
 * @param trainNo     列车编号，全局唯一
 * @param reactionT   反应时间 t，秒，正十进制、最多 3 位小数
 * @param baseDecelA  基础减速度 a，米每平方秒，正十进制、最多 3 位小数
 * @param version     配置版本，创建为 1，每次成功修改加一
 */
public record TrainProfile(long id, String trainNo, BigDecimal reactionT, BigDecimal baseDecelA,
                           int version) {
}
