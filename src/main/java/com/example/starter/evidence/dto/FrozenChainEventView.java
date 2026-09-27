package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 冻结保管链中的单个链事件视图（按截至时刻重放后的有序事件）。
 * 不同 kind 使用不同字段组，其余为 null。
 *
 * @param seq            链内序号，从 1 开始（入库为第 1 个事件）
 * @param kind           事件类型：INTAKE 入库 / TRANSFER 交接 / LOAN_BORROW 借出 / LOAN_RETURN 归还 / INSPECTION 封签核验
 * @param occurredAt     事件发生时刻（交接/核验为 Asia/Shanghai，借出/归还为 UTC，均按历史记录原值冻结）
 * @param actorId        事件操作人
 * @param fromCustodian  交接发起人保管人（仅 TRANSFER）
 * @param toCustodian    交接接收人（仅 TRANSFER）
 * @param transferStatus 交接决定状态（仅 TRANSFER）
 * @param decidedAt      交接决定时刻（仅 TRANSFER）
 * @param borrowerId     借用人（仅借出/归还事件）
 * @param loanStatus     借出状态（仅借出/归还事件）
 * @param dueAt          应还时刻 UTC（仅借出/归还事件）
 * @param returnedAt     实际归还时刻 UTC（仅 LOAN_RETURN）
 * @param passed         核验或归还封签结果（INSPECTION / LOAN_RETURN）
 */
public record FrozenChainEventView(
        int seq,
        String kind,
        LocalDateTime occurredAt,
        String actorId,
        String fromCustodian,
        String toCustodian,
        String transferStatus,
        LocalDateTime decidedAt,
        String borrowerId,
        String loanStatus,
        LocalDateTime dueAt,
        LocalDateTime returnedAt,
        Boolean passed) {
}
