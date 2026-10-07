package com.example.starter.blind.dto;

/**
 * 创建实验请求体。mode 缺省为 BLOCK；
 * BLOCK 需要 blockCount（2~8）且不得携带 capacity；
 * MINIMIZE 需要 capacity（偶数 4~20，A、B 各半）且不得携带 blockCount，区组统一为 1。
 * 具体取值校验在服务层完成，保证两种模式各自的约束互不干扰。
 *
 * @param blockCount 区组数量，仅 BLOCK 模式使用，创建时固定；每组固定 4 个席位
 * @param mode       分配模式：BLOCK（默认）/ MINIMIZE
 * @param capacity   总容量，仅 MINIMIZE 模式使用，偶数 4~20
 */
public record CreateExperimentRequest(
        Integer blockCount,
        String mode,
        Integer capacity
) {
}
