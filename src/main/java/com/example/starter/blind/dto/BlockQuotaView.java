package com.example.starter.blind.dto;

/**
 * 区组名额视图：只含区组编号与名额，不含任何处理映射。
 *
 * @param blockCode 区组编号，不表示处理组
 * @param quota     名额，单位：人次
 */
public record BlockQuotaView(String blockCode, int quota) {
}
