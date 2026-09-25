package com.example.starter.plan.model;

import java.util.List;

/**
 * 计划编组登记记录（含规范化后的车厢集合）。
 *
 * @param planId       所属计划 id
 * @param version      编组版本，随计划版本一起递增
 * @param trainLength  编组长度，单位米，必须为正
 * @param platformCode 登记停靠站台代码
 * @param operator     操作者标识
 * @param cars         车厢编号，按编号去重并规范化升序排列
 */
public record Consist(long planId, int version, int trainLength, String platformCode,
                      String operator, List<String> cars) {
}
