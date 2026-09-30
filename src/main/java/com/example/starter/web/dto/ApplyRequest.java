package com.example.starter.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 船舶靠泊申请请求。
 *
 * @param requestId      幂等键；同一 requestId 同参重放返回首次结果，异参返回 409
 * @param vesselName     合成船舶名称
 * @param draftM         船舶吃水，单位米，必须为正数
 * @param safetyMarginM  安全余量要求，单位米，必须为非负数
 * @param berthCode      申请泊位编码
 * @param startTime      靠泊起始时间（含），格式 yyyy-MM-dd HH:mm:ss，业务时区 Asia/Shanghai
 * @param endTime        靠泊结束时间（不含），必须晚于起始时间
 */
public record ApplyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 128) String vesselName,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal draftM,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal safetyMarginM,
        @NotBlank @Size(max = 64) String berthCode,
        @NotNull @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
        @NotNull @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
}
