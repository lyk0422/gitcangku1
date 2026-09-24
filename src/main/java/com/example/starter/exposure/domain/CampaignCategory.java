package com.example.starter.exposure.domain;

/**
 * 公告类别。
 * <ul>
 *     <li>CRITICAL：紧急公告，静默时段内仅在访客 allowCritical=true 时放行；</li>
 *     <li>SERVICE：服务公告，访客静默时段内一律抑制；</li>
 *     <li>MARKETING：营销公告，访客静默时段内一律抑制。</li>
 * </ul>
 */
public enum CampaignCategory {
    CRITICAL,
    SERVICE,
    MARKETING
}
