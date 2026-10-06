package com.example.starter.plan.web.dto;

/**
 * 变电所响应，含当前功率上限与参数版本。
 */
public record SubstationResponse(String substationKey, int powerLimitKw, int version) {
}
