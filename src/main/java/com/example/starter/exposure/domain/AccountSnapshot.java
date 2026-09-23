package com.example.starter.exposure.domain;

/**
 * 活动账本快照：预算转移激活前后各活动状态的冻结证据。
 *
 * @param campaignId 活动编号
 * @param budget     快照时刻总预算，单位次
 * @param inFlight   快照时刻在途预占数，单位次
 * @param confirmed  快照时刻已确认曝光数，单位次
 * @param version    快照时刻账本版本号
 */
public record AccountSnapshot(
        String campaignId,
        int budget,
        int inFlight,
        int confirmed,
        int version
) implements Comparable<AccountSnapshot> {

    /** 可转余额：只计算尚未预占的预算。 */
    public int transferable() {
        return budget - inFlight - confirmed;
    }

    /** 稳定排序：按活动编号字典序。 */
    @Override
    public int compareTo(AccountSnapshot other) {
        return campaignId.compareTo(other.campaignId);
    }

    public static AccountSnapshot of(BudgetAccount account) {
        return new AccountSnapshot(account.campaignId(), account.budget(),
                account.inFlight(), account.confirmed(), account.version());
    }
}
