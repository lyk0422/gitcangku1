package com.example.starter.plan.model;

/**
 * 乘务员（可担当乘务组成员的人员）主数据。
 *
 * @param id        主键
 * @param memberKey 人员业务键（工号），全局唯一
 * @param name      姓名（仅展示用）
 */
public record CrewMember(long id, String memberKey, String name) {
}
