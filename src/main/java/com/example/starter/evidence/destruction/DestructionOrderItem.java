package com.example.starter.evidence.destruction;

/**
 * 销毁令入列证物实体，对应 destruction_order_item 表。创建时写入后不可变。
 *
 * @param id             主键
 * @param destructionKey 所属销毁令业务键
 * @param evidenceKey    入列证物业务键
 * @param position       提交列表中的原始位置（从 0 开始），逐件原因按原序返回
 */
public record DestructionOrderItem(
        Long id,
        String destructionKey,
        String evidenceKey,
        int position) {
}
