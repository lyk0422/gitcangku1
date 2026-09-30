package com.example.starter.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 潮高区间持久化对象；height 为潮高（米）。
 */
public record TideSegmentPo(
        Long id,
        Long versionId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        BigDecimal height) {
}
