package com.example.starter.plan.model;

/**
 * 列车牵引档案。
 *
 * @param id           主键
 * @param trainNo      列车编号，全局唯一
 * @param tractionMode 牵引方式：ELECTRIC 电力 / DIESEL 柴油
 * @param version      档案版本，从 1 开始，每次变更成功加一
 */
public record TrainProfile(long id, String trainNo, TractionMode tractionMode, int version) {
}