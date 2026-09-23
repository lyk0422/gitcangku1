package com.example.starter.race.domain;

import java.util.List;

/**
 * 团队配置视图：团队代码与其全部成员参赛号（集合，3~5个，顺序无业务含义）。
 *
 * @param teamCode 团队代码，赛事内唯一
 * @param bibs     全部成员参赛号；同一选手最多属于一个团队
 */
public record TeamView(String teamCode, List<String> bibs) {
}
