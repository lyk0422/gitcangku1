package com.example.starter.api.dto;

/**
 * 撤销应急走廊结果。
 *
 * @param corridorId      走廊唯一标识
 * @param status          走廊状态：REVOKED
 * @param airspaceVersion 撤销生效后的全局空域版本
 */
public record CorridorRevokeResult(String corridorId, String status, long airspaceVersion) {
}
