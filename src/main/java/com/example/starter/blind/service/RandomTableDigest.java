package com.example.starter.blind.service;

import com.example.starter.blind.repo.ExperimentRepository.SeatRow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 随机表摘要：对区组完整序列（席位号=处理代码，按序号升序）计算 SHA-256 hex。
 * 摘要用于封存前校验与封存记录存证，不可逆推具体序列。
 */
final class RandomTableDigest {

    private RandomTableDigest() {
    }

    /**
     * 计算区组随机表摘要。
     *
     * @param experimentId 实验编号
     * @param blockNo      区组号
     * @param capacity     版本覆盖的累计容量
     * @param seats        区组席位（须按 seatNo 升序，数量等于 capacity）
     * @return 64 位小写 hex 摘要
     */
    static String sha256(String experimentId, int blockNo, int capacity, List<SeatRow> seats) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(experimentId).append('|').append(blockNo).append('|').append(capacity);
        for (SeatRow seat : seats) {
            canonical.append('|').append(seat.seatNo()).append('=').append(seat.treatment());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
