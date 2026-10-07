package com.example.starter.api.dto;

/**
 * 锁文件中冻结的单条 peer 匹配依据。
 *
 * <p>记录锁定时刻某声明方制品（精确版本）对某宿主名称的闭区间要求，
 * 以及普通依赖图中实际匹配到的宿主精确版本；后续撤回/登记不改变旧锁。
 *
 * @param declarerName    声明该 peer 的制品名称
 * @param declarerVersion 声明该 peer 的制品精确版本
 * @param hostName        peer 宿主名称（普通依赖图已可达）
 * @param minimumVersion  peer 要求的宿主最低版本（含），正整数
 * @param maximumVersion  peer 要求的宿主最高版本（含），正整数
 * @param matchedVersion  实际匹配的宿主精确版本，正整数
 */
public record LockPeerMatchResponse(
        String declarerName,
        int declarerVersion,
        String hostName,
        int minimumVersion,
        int maximumVersion,
        int matchedVersion) {
}
