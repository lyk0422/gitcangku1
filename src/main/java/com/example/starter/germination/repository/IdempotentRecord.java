package com.example.starter.germination.repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 幂等请求记录；与业务数据在同一事务内提交或回滚。
 *
 * @param requestId     请求号
 * @param operation     操作类型
 * @param paramsHash    规范化参数哈希
 * @param done          true 已完成并固化响应；false 处理中（异常回滚后不会残留）
 * @param responseJson  首次成功响应 JSON；处理中为 null
 * @param createdAt     首次到达时刻
 */
public record IdempotentRecord(String requestId,
                               String operation,
                               String paramsHash,
                               boolean done,
                               String responseJson,
                               Instant createdAt) {
}
