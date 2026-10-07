package com.example.starter.blind.dto;

import jakarta.validation.constraints.Size;

/**
 * 创建实验请求体。
 *
 * @param mode       分配模式：BLOCK=固定区组（缺省）；MINIMIZE=协变量最小化
 * @param blockCount BLOCK 模式的区组数量，2～8，创建时固定；每组固定 4 个席位
 * @param capacity   MINIMIZE 模式的总容量，4～20 的偶数，A/B 各半；区组统一为 1
 */
public record CreateExperimentRequest(
        @Size(max = 16, message = "mode 最长 16 字符")
        String mode,
        Integer blockCount,
        Integer capacity
) {
}
