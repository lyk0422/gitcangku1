package com.example.starter.blind.dto;

import java.util.List;

/**
 * 区组随机表版本链视图（历史查询）：按版本号升序返回全部封存记录。
 *
 * @param experimentId 实验编号
 * @param blockNo      区组号
 * @param seals        版本链，按版本号升序；封存不可撤销、不可删除
 */
public record BlockSealHistoryView(
        String experimentId,
        int blockNo,
        List<SealView> seals
) {
}
