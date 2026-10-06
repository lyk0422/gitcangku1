package com.example.starter.blind.dto;

import jakarta.validation.constraints.Min;

/**
 * 阶段开始/结束请求体；全部字段可选，空体等价于不带期望版本。
 *
 * @param expectedVersion 期望的当前阶段版本号；提供时与库内版本不一致则 409，
 *                        并作为幂等参数指纹的一部分参与同参/异参判定
 */
public record PhaseOperationRequest(
        @Min(value = 0, message = "expectedVersion 不能为负")
        Integer expectedVersion
) {
}
