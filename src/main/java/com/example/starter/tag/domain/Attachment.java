package com.example.starter.tag.domain;

import java.time.Instant;

/**
 * 标签附着记录（标签与动物的关联区间）。
 *
 * <p>区间采用 UTC 时间、左闭右开：{@code [attachAt, detachAt)}；
 * {@link #detachAt} 为 {@code null} 表示当前仍然有效（标签仍附着在该动物身上）。
 * 摘除时只回填结束时刻冻结上一段关联，不覆盖历史。
 */
public class Attachment {

    private Long id;
    private Long animalId;
    private Long tagId;
    /** 执行附着的站点主键。 */
    private Long siteId;
    /** 附着起始时刻（UTC，含）。 */
    private Instant attachAt;
    /** 摘除时刻（UTC，不含）；null 表示当前有效。 */
    private Instant detachAt;
    /** 完成摘除操作的站点主键；null 表示尚未摘除。 */
    private Long detachSiteId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAnimalId() {
        return animalId;
    }

    public void setAnimalId(Long animalId) {
        this.animalId = animalId;
    }

    public Long getTagId() {
        return tagId;
    }

    public void setTagId(Long tagId) {
        this.tagId = tagId;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public Instant getAttachAt() {
        return attachAt;
    }

    public void setAttachAt(Instant attachAt) {
        this.attachAt = attachAt;
    }

    public Instant getDetachAt() {
        return detachAt;
    }

    public void setDetachAt(Instant detachAt) {
        this.detachAt = detachAt;
    }

    public Long getDetachSiteId() {
        return detachSiteId;
    }

    public void setDetachSiteId(Long detachSiteId) {
        this.detachSiteId = detachSiteId;
    }
}
