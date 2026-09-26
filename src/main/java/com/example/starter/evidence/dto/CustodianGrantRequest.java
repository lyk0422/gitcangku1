package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 案件保管人授权请求（合成数据初始化入口）。
 *
 * @param custodianId 保管人标识
 */
public record CustodianGrantRequest(
        @NotBlank @Size(max = 64) String custodianId) {
}
