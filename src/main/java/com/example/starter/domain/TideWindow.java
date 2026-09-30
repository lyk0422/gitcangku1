package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 潮高区间，左闭右开 [startTime, endTime)。
 *
 * @param startTime    区间起始时间（含），业务时区 Asia/Shanghai
 * @param endTime      区间结束时间（不含）
 * @param tideHeightM  该区间潮高，单位米，允许为负
 */
public record TideWindow(LocalDateTime startTime, LocalDateTime endTime, BigDecimal tideHeightM) {
}
