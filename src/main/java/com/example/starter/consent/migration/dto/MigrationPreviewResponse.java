package com.example.starter.consent.migration.dto;

import java.util.List;

/**
 * 迁移预览结果：列出绑定旧用途的全部有效授权、已撤回授权与数据记录，
 * 并按记录属性给出唯一目标用途或 UNMAPPED；预览不写任何数据。
 *
 * @param catalogVersion   预览基于的目录代次
 * @param sourcePurpose    被拆分的旧用途
 * @param sourceRangeStart 旧用途处理范围起点（左闭，含）
 * @param sourceRangeEnd   旧用途处理范围终点（右开，不含）
 * @param activeGrants     绑定旧用途的全部有效授权（激活时必须逐个提交 expectedVersion）
 * @param revokedGrants    绑定旧用途的全部已撤回授权（保留历史旧用途，不参与迁移）
 * @param records          绑定旧用途的全部数据记录及唯一目标用途或 UNMAPPED
 */
public record MigrationPreviewResponse(long catalogVersion,
                                       String sourcePurpose,
                                       long sourceRangeStart,
                                       long sourceRangeEnd,
                                       List<ActiveGrantItem> activeGrants,
                                       List<RevokedGrantItem> revokedGrants,
                                       List<RecordItem> records) {

    /**
     * 有效授权预览项。
     *
     * @param subjectKey      主体标识
     * @param epoch           授权代次
     * @param expectedVersion 激活时须原样回传的授权版本
     * @param targetPurpose   按授权原范围拆分到的目标用途；授权覆盖多个目标时返回全部
     */
    public record ActiveGrantItem(String subjectKey, int epoch, int expectedVersion,
                                  List<String> targetPurposes) {
    }

    /**
     * 已撤回授权预览项：迁移不恢复其数据，只能保留历史旧用途。
     *
     * @param subjectKey 主体标识
     * @param epoch      授权代次
     * @param status     固定为 REVOKED
     */
    public record RevokedGrantItem(String subjectKey, int epoch, String status) {
    }

    /**
     * 数据记录预览项。
     *
     * @param subjectKey       主体标识
     * @param epoch            所属授权代次
     * @param recordKey        记录键
     * @param recordAttribute  记录属性
     * @param expectedVersion  激活时须原样回传的记录版本
     * @param grantActive      所属授权是否仍为 ACTIVE（false 表示随已撤回授权隔离，不迁移）
     * @param targetPurpose    按属性命中的唯一目标用途；未命中为 UNMAPPED
     */
    public record RecordItem(String subjectKey, int epoch, String recordKey, long recordAttribute,
                             int expectedVersion, boolean grantActive, String targetPurpose) {
    }
}
