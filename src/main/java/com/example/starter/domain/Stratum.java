package com.example.starter.domain;

import java.time.Instant;

/**
 * 遗址层位：遗址编号+层位号唯一标识一个层位。
 *
 * @param id 自增主键
 * @param siteCode 遗址编号（合成数据）
 * @param stratumNo 层位号，与遗址编号联合唯一
 * @param description 层位描述，null 表示未填写
 * @param createdAt 登记时刻（UTC）
 */
public record Stratum(long id, String siteCode, String stratumNo, String description, Instant createdAt) {
}