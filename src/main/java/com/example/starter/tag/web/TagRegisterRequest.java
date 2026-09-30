package com.example.starter.tag.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 标签登记请求：将一个新标签登记到其首个持有站点。
 */
public class TagRegisterRequest {

    @NotBlank
    private String tagCode;

    @NotBlank
    private String siteCode;

    public String getTagCode() {
        return tagCode;
    }

    public void setTagCode(String tagCode) {
        this.tagCode = tagCode;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public void setSiteCode(String siteCode) {
        this.siteCode = siteCode;
    }
}
