package com.example.starter.blind.dto;

import java.util.List;

/**
 * 参与者样本链视图（仅提交者本人可查）：含全部登记/更正记录与锁定状态。
 *
 * @param experimentId  实验编号
 * @param participantId 合成参与者编号
 * @param version       当前链版本号（记录总数），锁定时作为期望版本校验
 * @param locked        是否已锁定配对
 * @param pairCode      随机配对码；未锁定前为 null
 * @param records       样本记录列表，按登记时间升序
 */
public record SampleChainView(
        String experimentId,
        String participantId,
        int version,
        boolean locked,
        String pairCode,
        List<SampleRecordView> records
) {
}