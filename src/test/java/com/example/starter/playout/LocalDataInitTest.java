package com.example.starter.playout;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地入口配置冒烟测试：默认 profile 使用 H2 内存库（MODE=MySQL）自动建表并初始化合成演示数据，
 * 不依赖外部 MySQL、Docker 或预先启动的应用；上下文随测试结束关闭，内存数据随 JVM 退出丢失。
 */
@SpringBootTest
class LocalDataInitTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void localProfileInitializesSchemaAndSyntheticData() {
        Integer assets = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_asset WHERE id IN ('demo-fallback', 'demo-a', 'demo-b')",
                Integer.class);
        assertThat(assets).isEqualTo(3);
        Integer fallback = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_channel WHERE id = 'demo'"
                        + " AND fallback_asset_id = 'demo-fallback'", Integer.class);
        assertThat(fallback).isEqualTo(1);
        // 混音表已自动建表
        Integer mixDrafts = jdbc.queryForObject("SELECT COUNT(*) FROM playout_mix_draft",
                Integer.class);
        assertThat(mixDrafts).isNotNull();
    }
}
