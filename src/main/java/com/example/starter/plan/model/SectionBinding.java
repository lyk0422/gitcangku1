package com.example.starter.plan.model;

/**
 * 区段-变电所绑定。未绑定区段保持原有时隙模式，不参与功率预算校验。
 *
 * @param id             主键
 * @param sectionId      区段 ID，全局唯一（一个区段最多绑定一个变电所）
 * @param substationKey  目标变电所业务键
 * @param version        绑定版本，从 1 起，改绑定成功一次加一
 */
public record SectionBinding(long id, String sectionId, String substationKey, int version) {
}
