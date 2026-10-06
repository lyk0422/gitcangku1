package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 封存箱树视图：自请求箱向下的完整子树，成员含证物与子箱（递归）。
 * 待接收冻结期间仍列出全部成员。
 *
 * @param containerKey 箱业务键
 * @param sealNo       箱独立封条编号
 * @param custodianId  当前保管人
 * @param status       箱状态
 * @param version      成员版本
 * @param depth        当前箱层级（根箱为 1，最多 3 层）
 * @param members      直接成员列表
 */
public record ContainerTreeView(
        String containerKey,
        String sealNo,
        String custodianId,
        String status,
        int version,
        int depth,
        List<Member> members) {

    /**
     * 树成员：证物为叶子，子箱携带递归子树。
     *
     * @param memberType  成员类型：EVIDENCE / BOX
     * @param memberKey   成员业务键
     * @param custodianId 成员当前保管人
     * @param status      成员当前状态
     * @param child       子箱子树；证物成员为 NULL
     */
    public record Member(
            String memberType,
            String memberKey,
            String custodianId,
            String status,
            ContainerTreeView child) {
    }
}
