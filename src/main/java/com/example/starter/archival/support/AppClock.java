package com.example.starter.archival.support;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务时间来源，固定使用 Asia/Shanghai；测试可注入可控时钟。
 */
public interface AppClock {

    /** 业务时区：所有时间字段均按此时区落库与展示。 */
    ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 当前业务时间。 */
    LocalDateTime now();
}
