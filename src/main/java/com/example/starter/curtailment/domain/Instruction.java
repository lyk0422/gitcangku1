package com.example.starter.curtailment.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 限发指令实体，对应表 curtailment_instruction。
 *
 * @param id           主键
 * @param instructionNo 指令业务编号，全局唯一
 * @param unitId       机组编号
 * @param startUtc     限发区间起点（UTC，左闭）
 * @param endUtc       限发区间终点（UTC，右开）
 * @param maxPowerKw   限发期间最大发电功率，单位 kW，必须为正
 * @param status       落库状态：PENDING / CONFIRMED / REVOKED
 * @param version      指令版本号：确认、撤销、采样更正时递增，对账时固化
 * @param createdAt    创建时间（UTC）
 * @param updatedAt    最近变更时间（UTC）
 */
public record Instruction(long id, String instructionNo, String unitId, Instant startUtc, Instant endUtc,
                          BigDecimal maxPowerKw, String status, int version, Instant createdAt, Instant updatedAt) {

    /**
     * 计算含派生状态的有效状态：已确认且当前时间越过终点视为 ENDED。
     *
     * @param now 当前 UTC 时间
     * @return 有效状态
     */
    public InstructionStatus effectiveStatus(Instant now) {
        InstructionStatus stored = InstructionStatus.valueOf(status);
        if (stored == InstructionStatus.CONFIRMED && !now.isBefore(endUtc)) {
            return InstructionStatus.ENDED;
        }
        return stored;
    }
}