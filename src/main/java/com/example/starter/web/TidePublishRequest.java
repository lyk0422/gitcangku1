package com.example.starter.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 潮高版本发布请求；区间必须首尾相接覆盖整个版本时间轴。
 */
public record TidePublishRequest(
        @NotNull LocalDateTime rangeStart,
        @NotNull LocalDateTime rangeEnd,
        @NotEmpty @Valid List<Segment> segments) {

    /**
     * 单个潮高区间：左闭右开，潮高单位米。
     */
    public record Segment(
            @NotNull LocalDateTime startTime,
            @NotNull LocalDateTime endTime,
            @NotNull BigDecimal height) {
    }
}
