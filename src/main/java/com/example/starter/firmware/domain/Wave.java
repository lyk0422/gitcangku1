package com.example.starter.firmware.domain;

/**
 * 拓扑波次，领取成功即冻结成员集合与图。
 *
 * @param id          波次ID
 * @param releaseId   所属发布单ID
 * @param waveNo      波次序号，从1开始按领取顺序递增
 * @param memberCount 成员数，取值1~5
 */
public record Wave(long id, long releaseId, int waveNo, int memberCount) {
}
