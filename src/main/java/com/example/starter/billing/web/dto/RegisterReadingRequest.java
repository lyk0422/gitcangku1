package com.example.starter.billing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 计量读数登记请求。读数不可变：相同 kind 再次提交即视为更正，生成版本号更大的校正记录。
 *
 * @param kind             读数种类：BASELINE 基线 / ACTUAL 实际
 * @param valueKwh         电量读数，单位 kWh，不允许为负
 * @param periodStart      计量时段起点（UTC，左闭）
 * @param periodEnd        计量时段终点（UTC，右开），必须晚于起点且与邀约时段一致
 * @param correctionReason 更正缘由；首次读数留空，更正时必填
 */
public record RegisterReadingRequest(
        @NotBlank(message = "读数种类不能为空")
        String kind,
        @NotNull(message = "电量读数不能为空")
        BigDecimal valueKwh,
        @NotNull(message = "时段起点不能为空")
        Instant periodStart,
        @NotNull(message = "时段终点不能为空")
        Instant periodEnd,
        String correctionReason) {
}
