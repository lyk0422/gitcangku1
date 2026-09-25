package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 批量入库清单项。逐项数据格式（描述非空、重量为正的两位小数、证物键非空）不在 Bean Validation
 * 中拦截，而由服务端统一校验后返回 422 及逐项原因（字段级注解缺失只返回 400，无法携带逐项原因）。
 *
 * @param evidenceKey    证物业务键，同批次内不得重复且系统中不得已存在
 * @param description    申报描述，不可为空（为空时整批 422 并标注该项）
 * @param declaredWeight 申报重量，单位千克(kg)，大于 0 且最多两位小数
 */
public record BatchIntakeItem(
        @Size(max = 64) String evidenceKey,
        @Size(max = 512) String description,
        BigDecimal declaredWeight) {
}
