package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 寻径发布计划冻结的路径段（发布后不可变，换图不回写）。
 *
 * @param planId        所属计划 id
 * @param graphVersion  寻径时冻结的图版本
 * @param seq           路径内区段序号，从 0 开始
 * @param sectionId     区段编号
 * @param fromStation   该段起点站
 * @param toStation     该段终点站
 * @param travelMinutes 该段行驶分钟
 * @param startUtc      该段占用开始（含），UTC
 * @param endUtc        该段占用结束（不含），UTC
 */
public record RouteLeg(long planId, int graphVersion, int seq, String sectionId,
                       String fromStation, String toStation, int travelMinutes,
                       Instant startUtc, Instant endUtc) {
}
