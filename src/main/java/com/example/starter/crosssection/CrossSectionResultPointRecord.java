package com.example.starter.crosssection;

/**
 * 成果冻结点：对应 cross_section_result_point 的一行。
 * 横向与高程来自同一观测点同一提交版本，严格按创建时提交顺序保存，禁止跨提交拼接。
 *
 * @param ordinal             提交顺序序号，从 1 开始
 * @param sourceObservationId 源断面观测点标识
 * @param sourceVersion       创建时核验的源点当前版本（冻结）
 * @param xMm                 冻结横向坐标（整数毫米）
 * @param zMm                 冻结地面高程（整数毫米）
 */
public record CrossSectionResultPointRecord(
        int ordinal,
        String sourceObservationId,
        int sourceVersion,
        int xMm,
        int zMm) {
}
