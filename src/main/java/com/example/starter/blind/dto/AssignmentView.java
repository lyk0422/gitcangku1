package com.example.starter.blind.dto;

/**
 * 普通分配视图：只返回盲码、区组号和参与者编号，不包含处理代码或席位序号。
 */
public record AssignmentView(
        String experimentId,
        String participantId,
        String blindCode,
        int blockNo,
        String status) {
}
