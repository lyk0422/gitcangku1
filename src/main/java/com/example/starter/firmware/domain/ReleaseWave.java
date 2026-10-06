package com.example.starter.firmware.domain;

/**
 * 拓扑波次，领取成功即冻结成员集合。
 *
 * @param id          波次ID
 * @param releaseId   所属发布单ID
 * @param waveSeq     波次序号，同一发布单内从1开始递增
 * @param memberCount 成员数，取值1~5
 */
public record ReleaseWave(long id, long releaseId, int waveSeq, int memberCount) {
}
