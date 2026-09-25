package com.example.starter.exposure.domain;

/**
 * 访客同意决定。
 * <ul>
 *     <li>ALLOW：在生效区间内允许对应类别曝光；</li>
 *     <li>DENY：在生效区间内拒绝对应类别曝光，且不可被更低版本的同意覆盖。</li>
 * </ul>
 */
public enum ConsentDecision {
    ALLOW,
    DENY
}
