package com.example.starter.blind.dto;

/**
 * 退役批次的区组配额重算快照（不可变）。数量单位均为“个序号（席位）”。
 *
 * @param blockNo         区组号，从 1 开始
 * @param availableBefore 退役前区组可分配名额（未占用序号数；此前退役封存的序号不计入），实际值
 * @param sealedInBlock   本次退役在区组内封存的序号数，实际值
 * @param availableAfter  退役后区组可分配名额 = availableBefore - sealedInBlock，实际值
 * @param quotaAAfter     退役后区组内代码 A 的有效配额（未占用且未封存序号数），实际值
 * @param quotaBAfter     退役后区组内代码 B 的有效配额（未占用且未封存序号数），实际值
 */
public record BlockQuotaView(
        int blockNo,
        int availableBefore,
        int sealedInBlock,
        int availableAfter,
        int quotaAAfter,
        int quotaBAfter
) {
}
