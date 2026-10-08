package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 录入结果请求体。value 为整数，JSON 中的同值十进制（如 10 与 10.0）反序列化后等价；
 * expectedVersion 可选，提供时须与样本当前版本一致，否则 409。
 *
 * @param value           整数结果，必填，一样本仅可录入一次
 * @param expectedVersion 期望样本版本号，可选；用于幂等指纹与乐观校验
 */
public record RecordResultRequest(
        @NotNull(message = "value 不能为空")
        Integer value,
        Integer expectedVersion
) {
}
