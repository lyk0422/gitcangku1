package com.example.starter.blind.dto;

/**
 * 登记参与者请求体（仅 MINIMIZE 模式实验需要）。
 *
 * @param feature1 枚举特征一，仅允许 0 或 1
 * @param feature2 枚举特征二，仅允许 0 或 1
 */
public record AllocationCreateRequest(
        Integer feature1,
        Integer feature2
) {
}
