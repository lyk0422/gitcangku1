package com.example.starter.plan.web.dto;

/**
 * 变电所响应，含当前功率上限与上限版本。
 */
public record SubstationResponse(String substationKey, int powerCapKw, int version) {
}
