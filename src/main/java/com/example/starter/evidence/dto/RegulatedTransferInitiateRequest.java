package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 受监管交接发起请求。仅当前保管人可发起，接收人（同时为运送人）必须与发起人不同；
 * 按顺序声明 2 至 8 个检查点的 UTC 截止时刻，截止必须严格递增，名单创建后不可修改。
 *
 * @param requestId   幂等请求键
 * @param toCustodian 指定接收人（受监管交接中同时为运送人）
 * @param deadlines   各检查点 UTC 截止时刻（有序，第 i 个对应点号 i+1），必须严格递增
 */
public record RegulatedTransferInitiateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String toCustodian,
        @NotNull @Size(min = 2, max = 8) List<@NotNull LocalDateTime> deadlines) {
}