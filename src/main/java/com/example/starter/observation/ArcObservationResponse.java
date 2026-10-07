package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 圆弧观测记录响应：普通版本携带全部圆弧分量；删除墓碑版本只返回删除状态和版本。
 *
 * @param observationId   圆弧观测记录唯一标识
 * @param version         版本号
 * @param deleted         是否为删除墓碑
 * @param conflictPending 是否存在未解决冲突（true 时禁止参与裁定）
 * @param arcs            圆弧分量列表，按起始角升序（墓碑版本不返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArcObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        boolean conflictPending,
        List<ArcComponent> arcs) {

    /**
     * 由快照构造响应；墓碑版本自动省略圆弧分量。
     */
    public static ArcObservationResponse of(ArcObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ArcObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    snapshot.conflictPending(), null);
        }
        return new ArcObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.conflictPending(), snapshot.arcs());
    }
}
