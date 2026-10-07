package com.example.starter.blind.dto;

/**
 * 登记参与者请求体。仅 MINIMIZE 模式实验需要两种枚举特征；
 * BLOCK 模式实验不得携带特征。取值合法性（0/1）在服务层校验。
 *
 * @param feature1 枚举特征一，仅允许 0 或 1
 * @param feature2 枚举特征二，仅允许 0 或 1
 */
public record RegisterRequest(
        Integer feature1,
        Integer feature2
) {
}
