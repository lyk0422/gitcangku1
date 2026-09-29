package com.example.starter.consent.dto;

/**
 * 清除结果：返回被标记清除的对象数量。
 *
 * @param subjectKey     主体标识
 * @param purpose        用途
 * @param epoch          被清除的授权代次
 * @param clearedRecords 实际新标记清除的对象数（单位：对象数）
 */
public record ClearResponse(String subjectKey, com.example.starter.consent.Purpose purpose,
                            int epoch, int clearedRecords) {
}
