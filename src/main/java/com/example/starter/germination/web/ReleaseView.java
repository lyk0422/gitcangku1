package com.example.starter.germination.web;

import java.time.Instant;

/**
 * 放行状态视图。
 *
 * @param released    是否已放行
 * @param certNo      放行依据的证书编号；未放行为 null
 * @param releasedAt  放行时刻；未放行为 null
 */
public record ReleaseView(boolean released, String certNo, Instant releasedAt) {
}
