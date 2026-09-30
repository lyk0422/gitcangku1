package com.example.starter.race.domain;

/**
 * 组别与组别重分的纯逻辑：容量校验，不涉及数据库与时间。
 */
public final class CategoryRules {

    /** 申请/登记未指定组别时使用的默认组别代码。 */
    public static final String DEFAULT_CATEGORY = "OPEN";

    private CategoryRules() {
    }

    /**
     * 校验一次批量迁移后目标组别的在册人数是否超出容量。
     *
     * @param categoryCode 目标组别代码
     * @param currentActive 该组别当前在赛人数
     * @param incoming      本批迁入该组别的人数
     * @param outgoing      本批迁出该组别的人数
     * @param capacity      组别容量；null 表示不限容量
     * @return 违规描述；未超容量返回 null
     */
    public static String capacityViolation(
            String categoryCode,
            int currentActive,
            int incoming,
            int outgoing,
            Integer capacity) {
        if (capacity == null) {
            return null;
        }
        int finalCount = currentActive + incoming - outgoing;
        if (finalCount > capacity) {
            return "组别 " + categoryCode + " 容量不足: 容量=" + capacity
                    + ", 迁移后在册=" + finalCount;
        }
        return null;
    }
}
