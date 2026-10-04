package com.example.starter.plan.model;

/**
 * 发布时冻结的牵引快照：牵引方式与每段档案值、版本。
 * 快照随发布（含改签发布）写入，之后不修改、不删除，后续档案合法变更不改历史。
 *
 * @param planId         所属计划 id
 * @param seq            占用在计划内的序号，与占用明细对应
 * @param trainNo        列车编号（发布时）
 * @param tractionMode   发布时牵引方式
 * @param sectionId      区段 ID
 * @param electrified    发布时区段是否供电
 * @param dieselAllowed  发布时区段是否允许柴油
 * @param sectionVersion 发布时区段档案版本
 */
public record TractionSnapshot(long planId, int seq, String trainNo, TractionMode tractionMode,
                               String sectionId, boolean electrified, boolean dieselAllowed,
                               int sectionVersion) {
}