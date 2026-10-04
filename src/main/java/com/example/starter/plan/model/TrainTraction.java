package com.example.starter.plan.model;

/**
 * 列车牵引档案，每列车一条，可 upsert 变更。
 *
 * @param trainNo      列车编号，全局唯一
 * @param tractionMode 牵引方式
 */
public record TrainTraction(String trainNo, TractionMode tractionMode) {
}