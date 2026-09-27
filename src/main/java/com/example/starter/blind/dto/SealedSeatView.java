package com.example.starter.blind.dto;

/**
 * 封存序号视图：仅出现在退役明细（COORDINATOR 限定）中；
 * 封存的都是未分配席位，不含任何参与者的盲底信息。
 *
 * @param treatment 封存席位对应的处理代码
 * @param blockNo   封存席位所在区组号
 * @param seatNo    封存席位号
 * @param sealedAt  封存时间，Unix 毫秒，UTC
 */
public record SealedSeatView(
        String treatment,
        int blockNo,
        int seatNo,
        long sealedAt
) {
}
