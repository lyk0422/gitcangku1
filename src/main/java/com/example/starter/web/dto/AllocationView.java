package com.example.starter.web.dto;

/**
 * 普通登记视图：仅含随机无含义盲码、区组号与参与者编号，
 * 不包含处理代码或可直接解码的席位序号。
 *
 * @param blindCode     随机无含义盲码
 * @param blockNo       区组号（从 1 开始）
 * @param participantId 参与者编号
 * @param status        分配状态（ENROLLED / WITHDRAWN）
 */
public record AllocationView(
        String blindCode,
        int blockNo,
        String participantId,
        String status
) {
}
