package com.example.starter.consent.dto;

/**
 * 批次查询快照中的单条主体记录：固化授权代次与证明版本，后续撤销/续签不影响本快照。
 *
 * @param subjectKey       主体标识
 * @param epoch            快照时该主体当前授权代次
 * @param attestationId    快照所用证明逻辑标识
 * @param attestationVersion 快照所用证明版本号
 * @param recordKey        记录键
 * @param payload          记录内容
 */
public record SnapshotItem(String subjectKey, int epoch, String attestationId,
                           int attestationVersion, String recordKey, String payload) {
}
