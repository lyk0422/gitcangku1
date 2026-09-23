package com.example.starter.blind;

import java.util.List;

/**
 * 实验内职责类型（与 X-Role 登录角色正交）：由职责轮换单在实验范围内授权。
 */
public enum DutyRole {
    /** 数据采集：仅见受试者编号、盲码与在组状态。 */
    DATA_COLLECTOR(List.of("participantId", "blindCode", "status")),
    /** 随机化保管：维护席位与处理映射，可见完整盲底字段。 */
    RANDOMIZATION_CUSTODIAN(
            List.of("participantId", "blindCode", "blockNo", "seatNo", "treatment")),
    /** 安全审阅：可见受试者盲码视图与揭盲历史。 */
    SAFETY_REVIEWER(List.of("participantId", "blindCode", "status", "unblindHistory"));

    /** 该职责完成工作所需的最小字段清单。 */
    private final List<String> minimalFields;

    DutyRole(List<String> minimalFields) {
        this.minimalFields = minimalFields;
    }

    /** 最小字段清单，逗号分隔，用于授权落库与审计。 */
    public String minimalFieldList() {
        return String.join(",", minimalFields);
    }
}
