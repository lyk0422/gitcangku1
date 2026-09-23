package com.example.starter.blind.dto;

import java.util.List;

/**
 * 职责轮换单视图：前后名册、授权代次与知情冲突依据，只读。
 *
 * @param rotationKey               轮换单号
 * @param experimentId              实验编号
 * @param actorId                   发起人（实验负责人）
 * @param expectedExperimentVersion 请求携带的期望版本号
 * @param beforeRoster              轮换前名册
 * @param afterRoster               轮换后名册
 * @param beforeGenerationNo        轮换前活动授权代次序号
 * @param afterGenerationNo         轮换后新授权代次序号
 * @param knowledgeBasis            激活时的知情历史依据（已通过揭盲知悉分组的人员与受试者）
 * @param effectiveAt               新代次生效时刻，Unix 毫秒，UTC
 * @param activatedAt               激活时间，Unix 毫秒，UTC
 */
public record RoleRotationView(
        String rotationKey,
        String experimentId,
        String actorId,
        int expectedExperimentVersion,
        RosterView beforeRoster,
        RosterView afterRoster,
        int beforeGenerationNo,
        int afterGenerationNo,
        List<KnowledgeFact> knowledgeBasis,
        long effectiveAt,
        long activatedAt
) {
    /** 知情事实：某人员已通过批准揭盲知悉某受试者分组（不含分组内容）。 */
    public record KnowledgeFact(String participantId, List<String> knownBy) {
    }
}
