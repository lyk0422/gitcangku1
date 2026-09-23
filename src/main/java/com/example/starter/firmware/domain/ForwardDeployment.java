package com.example.starter.firmware.domain;

/**
 * 设备已完成（SUCCESS）的正向投放历史，按完成时间先后排列，用于构造连续反向回退路径。
 *
 * @param releaseId   正向投放发布单ID
 * @param taskId      正向投放任务ID
 * @param fromVersion 当时来源版本
 * @param toVersion   当时升级到的版本
 * @param taskSeq     任务自增ID，作为完成先后的排序依据
 */
public record ForwardDeployment(long releaseId, long taskId, String fromVersion,
                                String toVersion, long taskSeq) {
}
