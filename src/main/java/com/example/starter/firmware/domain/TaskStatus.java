package com.example.starter.firmware.domain;

/**
 * 投放任务状态。
 * PENDING：分片接收中（有分片清单的发布单）或待回执（无分片清单的遗留发布单）；
 * INTEGRITY_FAILED：分片完整性失败（缺失/重复/摘要不匹配/越界），禁止安装与成功回执，不计入失败率；
 * INSTALLABLE：全部分片存在、顺序完整且聚合摘要匹配，等待安装回执；
 * SUCCESS：安装成功；FAILED：安装失败；CANCELLED：任务撤销/发布单取消。
 */
public enum TaskStatus {
    PENDING,
    INTEGRITY_FAILED,
    INSTALLABLE,
    SUCCESS,
    FAILED,
    CANCELLED
}
