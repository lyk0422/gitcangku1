package com.example.starter.crosssection;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 断面观测点响应：普通版本携带完整版本组；删除墓碑版本只返回删除状态和版本，不带业务字段。
 *
 * @param observationId 断面观测点唯一标识
 * @param version       当前（或所查询历史）版本号
 * @param deleted       是否为删除墓碑
 * @param sectionNo     断面号（墓碑版本不返回）
 * @param xMm           横向坐标，整数毫米（墓碑版本不返回）
 * @param zMm           地面高程，整数毫米（墓碑版本不返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrossSectionResponse(
        String observationId,
        int version,
        boolean deleted,
        String sectionNo,
        Integer xMm,
        Integer zMm) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段。
     */
    public static CrossSectionResponse of(CrossSectionSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new CrossSectionResponse(snapshot.observationId(), snapshot.version(), true, null, null, null);
        }
        return new CrossSectionResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.sectionNo(), snapshot.xMm(), snapshot.zMm());
    }
}
