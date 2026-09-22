package com.example.starter.domain;

/**
 * 额度账持久化对象。held_count 为 RESERVED+CONFIRMED 占用，恒满足 0 <= held_count <= cap。
 */
public class QuotaAccount {

    public static final String SCOPE_CAMPAIGN = "CAMPAIGN";
    public static final String SCOPE_VISITOR = "VISITOR";

    private long id;
    private String scope;
    private String quotaKey;
    private String utcDate;
    private int cap;
    private int heldCount;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getQuotaKey() {
        return quotaKey;
    }

    public void setQuotaKey(String quotaKey) {
        this.quotaKey = quotaKey;
    }

    public String getUtcDate() {
        return utcDate;
    }

    public void setUtcDate(String utcDate) {
        this.utcDate = utcDate;
    }

    public int getCap() {
        return cap;
    }

    public void setCap(int cap) {
        this.cap = cap;
    }

    public int getHeldCount() {
        return heldCount;
    }

    public void setHeldCount(int heldCount) {
        this.heldCount = heldCount;
    }
}
