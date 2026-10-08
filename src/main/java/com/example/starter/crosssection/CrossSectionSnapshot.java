package com.example.starter.crosssection;

/**
 * 断面观测点快照：对应 cross_section_current 当前状态或 cross_section_version 历史版本的一行。
 * 断面号、横向毫米、高程毫米是一个完整版本组，永远一起版本化。
 *
 * @param observationId 断面观测点唯一标识
 * @param version       版本号，从 1 开始
 * @param sectionNo     断面号（文本原样比较与展示）；删除墓碑版本为 null
 * @param xMm           横向坐标（整数毫米）；删除墓碑版本为 null
 * @param zMm           地面高程（整数毫米）；删除墓碑版本为 null
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 */
public record CrossSectionSnapshot(
        String observationId,
        int version,
        String sectionNo,
        Integer xMm,
        Integer zMm,
        boolean deleted) {
}
