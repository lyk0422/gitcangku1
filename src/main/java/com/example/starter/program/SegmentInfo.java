package com.example.starter.program;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 程序段视图：按 UTC 连续覆盖烧制区间的一段，给定目标温度与允许偏差。
 *
 * @param id          段ID
 * @param seq         段序号，从1开始
 * @param startTime   段起点，UTC，含起点
 * @param endTime     段终点，UTC，仅末段含终点
 * @param targetTempC 目标温度，摄氏度
 * @param toleranceC  允许偏差，摄氏度，非负
 */
public record SegmentInfo(long id, int seq, Instant startTime, Instant endTime,
                          BigDecimal targetTempC, BigDecimal toleranceC) {
}