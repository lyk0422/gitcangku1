package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 铁路走廊日计划主记录。
 *
 * @param id                 主键
 * @param scheduleKey        计划业务键，全局唯一
 * @param opDate             运营日期（Asia/Shanghai 日历日）
 * @param version            版本号，草稿占用整体替换成功一次加一
 * @param status             计划状态
 * @param consistLengthM     编组长度（米），为空表示不做站台适配
 * @param powerSupplyType    编组需求供电制式，为空表示不做站台适配
 * @param accessibleRequired 编组是否有无障碍登乘需求，为空表示不做站台适配
 */
public record DayPlan(long id, String scheduleKey, LocalDate opDate, int version, PlanStatus status,
                      Integer consistLengthM, PowerSupplyType powerSupplyType,
                      Boolean accessibleRequired) {

    /** 该计划是否登记了编组信息（需要站台适配）。三项属性同时存在或同时为空。 */
    public boolean hasConsist() {
        return consistLengthM != null && powerSupplyType != null && accessibleRequired != null;
    }
}
