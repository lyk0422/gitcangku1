package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.DelegationStatus;
import com.example.starter.consent.Purpose;

/**
 * 委托边响应：返回委托边的身份、版本、状态与到期时刻。
 *
 * @param delegationKey 委托边业务唯一键
 * @param subjectKey    授权主体标识
 * @param purpose       用途
 * @param epoch         委托所属授权代次
 * @param delegatorKey  委托方标识
 * @param processorKey  受托处理方标识
 * @param version       边版本，从 1 开始
 * @param status        状态：ACTIVE 有效 / REVOKED 已撤销（到期不改状态，但不再有效）
 * @param expiresAt     委托到期时刻（UTC）
 */
public record DelegationResponse(String delegationKey, String subjectKey, Purpose purpose, int epoch,
                                 String delegatorKey, String processorKey, int version,
                                 DelegationStatus status, Instant expiresAt) {
}
