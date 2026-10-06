package com.example.starter.blind.dto;

/**
 * 交叉阶段普通视图：只含无含义盲码、派生阶段状态与各阶段时刻。
 * 严禁包含处理代码 treatment 或可直接解码的席位号 seatNo；
 * 第二阶段处理为第一阶段隐藏代码的对侧代码，同样不得出现在本视图。
 *
 * @param experimentId    实验编号
 * @param participantId   合成参与者编号
 * @param blindCode       随机无含义盲码
 * @param phaseState      派生阶段状态：NOT_STARTED / FIRST_RUNNING / WASHOUT / SECOND_RUNNING / DONE
 * @param washoutMinutes  创建时固定的洗脱时长，单位分钟
 * @param firstStartedAt  第一阶段开始时间，Unix 毫秒，UTC；null 表示未开始
 * @param firstEndedAt    第一阶段结束时间，Unix 毫秒，UTC；null 表示未结束
 * @param secondStartedAt 第二阶段开始时间，Unix 毫秒，UTC；null 表示未开始
 * @param secondEndedAt   第二阶段结束时间，Unix 毫秒，UTC；null 表示未结束
 * @param version         阶段推进版本号（0~4），每次成功开始/结束加 1
 */
public record PhaseView(
        String experimentId,
        String participantId,
        String blindCode,
        String phaseState,
        int washoutMinutes,
        Long firstStartedAt,
        Long firstEndedAt,
        Long secondStartedAt,
        Long secondEndedAt,
        long version
) {
}
