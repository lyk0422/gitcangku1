package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 栅格观测响应：普通版本携带完整 16 格图与版本来源；删除墓碑只返回删除状态和版本，不带格值。
 *
 * @param observationId 栅格观测唯一标识
 * @param version       当前版本号
 * @param baseVersion   该版本合并所基于的基版号（创建版本为 null）
 * @param operator      写入该版本的操作者标识（客户端来源）
 * @param deleted       是否为删除墓碑
 * @param cells         16 个格值，按行优先排列；墓碑版本不返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MaskResponse(
        String observationId,
        int version,
        Integer baseVersion,
        String operator,
        boolean deleted,
        List<Integer> cells) {

    /**
     * 由快照构造响应；墓碑版本自动省略格值。
     */
    public static MaskResponse of(MaskSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new MaskResponse(snapshot.observationId(), snapshot.version(), null,
                    snapshot.operator(), true, null);
        }
        return new MaskResponse(snapshot.observationId(), snapshot.version(), snapshot.baseVersion(),
                snapshot.operator(), false, snapshot.cells());
    }
}
