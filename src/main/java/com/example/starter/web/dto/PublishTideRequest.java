package com.example.starter.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 潮高版本发布请求：新版本整体替换未来校核所用潮高表。
 *
 * @param versionNo   新版本号，必须唯一且为正整数
 * @param publishedAt 发布时间；为空时取业务时钟当前时间，格式 yyyy-MM-dd HH:mm:ss
 * @param remark      备注
 * @param windows     潮高区间集合，需自行保证首尾相接不留缺口
 */
public record PublishTideRequest(
        @NotNull @Positive Integer versionNo,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime publishedAt,
        @Size(max = 255) String remark,
        @NotEmpty @Valid List<WindowDto> windows) {

    /**
     * 单个潮高区间，左闭右开。
     *
     * @param startTime    起始时间（含）
     * @param endTime      结束时间（不含）
     * @param tideHeightM  潮高，单位米，允许为负
     */
    public record WindowDto(
            @NotNull @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @NotNull @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @NotNull BigDecimal tideHeightM) {
    }
}
