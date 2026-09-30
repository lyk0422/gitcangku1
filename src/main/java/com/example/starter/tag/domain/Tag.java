package com.example.starter.tag.domain;

import java.time.Instant;

/**
 * 标签。当前持有站点 {@link #holderSiteId} 表示标签所有权归属；
 * 标签可在站点之间通过交接转移。
 */
public class Tag {

    private Long id;
    /** 标签业务编号，如 L。 */
    private String tagCode;
    /** 当前持有站点主键；交接确认后更新为接收站点。 */
    private Long holderSiteId;
    private Instant registeredAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTagCode() {
        return tagCode;
    }

    public void setTagCode(String tagCode) {
        this.tagCode = tagCode;
    }

    public Long getHolderSiteId() {
        return holderSiteId;
    }

    public void setHolderSiteId(Long holderSiteId) {
        this.holderSiteId = holderSiteId;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }
}
