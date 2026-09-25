package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批量入库请求。保管人随请求体提交；items 与 measuredWeights 按下标一一对应。
 * 清单内 evidenceKey 重复或已存在整批 400；单件数据格式非法（重量非正、描述为空等）整批 422。
 *
 * @param intakeKey      批次幂等键，全局唯一；同键同参（清单换序视为同参）重放首次结果
 * @param custodianId    保管人，入库后成为全部证物的当前保管人
 * @param items          证物清单，1～50 件
 * @param measuredWeights 实测重量列表（单位千克），与 items 等长并按下标一一对应
 */
public record BatchIntakeRequest(
        @NotBlank @Size(max = 64) String intakeKey,
        @NotBlank @Size(max = 64) String custodianId,
        @NotNull @Size(min = 1, max = 50) List<BatchIntakeItem> items,
        @NotNull @Size(min = 1, max = 50) List<BigDecimal> measuredWeights) {

    /**
     * 批量入库清单单项。字段级格式（非空、重量为正且两位小数）由服务层逐项校验并汇总为 422。
     *
     * @param evidenceKey    证物业务键，全局唯一
     * @param description    证物描述，非空
     * @param declaredWeight 申报重量（单位千克，大于0的两位小数）
     */
    public record BatchIntakeItem(
            @Size(max = 64) String evidenceKey,
            @Size(max = 512) String description,
            BigDecimal declaredWeight) {
    }
}
