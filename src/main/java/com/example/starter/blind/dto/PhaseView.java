package com.example.starter.blind.dto;

/**
 * 交叉实验阶段普通视图：只含盲码、阶段状态与各阶段时刻。
 * 严禁包含处理代码 treatment 或席位号 seatNo；第一阶段处理为原隐藏代码、
 * 第二阶段为另一代码，二者均只能通过受控揭盲流程获得。
 *
 * @param experimentId    实验编号
 * @param participantId   合成参与者编号
 * @param blindCode       随机无含义盲码
 * @param status          NOT_STARTED / FIRST_RUNNING / WASHOUT / SECOND_RUNNING / DONE
 * @param version         阶段状态版本号，每次迁移加 1
 * @param washoutMinutes  洗脱时长（分钟），创建实验时固定
 * @param firstStartedAt  第一阶段开始时间，Unix 毫秒，UTC；null 表示未开始
 * @param firstEndedAt    第一阶段结束时间，Unix 毫秒，UTC；null 表示未结束
 * @param secondStartedAt 第二阶段开始时间，Unix 毫秒，UTC；null 表示未开始
 * @param secondEndedAt   第二阶段结束时间，Unix 毫秒，UTC；null 表示未结束
 */
public record PhaseView(
        String experimentId,
        String participantId,
        String blindCode,
        String status,
        int version,
        int washoutMinutes,
        Long firstStartedAt,
        Long firstEndedAt,
        Long secondStartedAt,
        Long secondEndedAt
) {
}
