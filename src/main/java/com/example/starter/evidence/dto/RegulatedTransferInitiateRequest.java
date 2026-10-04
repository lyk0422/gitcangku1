package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 受监管交接发起请求。仅当前保管人可发起；声明 2-8 个有序检查点及每段 UTC 截止时刻，
 * 截止时刻须严格递增，名单创建后不改。
 *
 * @param requestId     幂等请求键
 * @param toCustodian   指定收件人，必须与发起人不同
 * @param transporterId 指定运送人，仅其可逐点签到
 * @param checkpoints   有序检查点名单，2-8 个，点号按声明顺序自 1 起编号
 */
public record RegulatedTransferInitiateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String toCustodian,
        @NotBlank @Size(max = 64) String transporterId,
        @NotNull @Size(min = 2, max = 8) List<@Valid CheckpointSpec> checkpoints) {

    /**
     * 单个检查点声明。
     *
     * @param deadlineAt 该点 UTC 截止时刻，须严格晚于上一点且晚于服务端当前 UTC 时刻
     */
    public record CheckpointSpec(
            @NotNull LocalDateTime deadlineAt) {
    }
}