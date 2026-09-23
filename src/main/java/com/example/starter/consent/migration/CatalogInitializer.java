package com.example.starter.consent.migration;

import org.springframework.stereotype.Component;

import com.example.starter.consent.Purpose;

import jakarta.annotation.PostConstruct;

/**
 * 初始用途目录引导：数据库为空时发布 catalogGeneration=1，
 * 内置 RESEARCH 与 PERSONALIZATION 两个互不影响的 ACTIVE 用途。
 *
 * <p>仅在本地/测试 H2 自动初始化场景下执行；已有目录数据时幂等跳过。
 */
@Component
public class CatalogInitializer {

    static final long INITIAL_RANGE_START = 0L;
    static final long INITIAL_RANGE_END = 1_000_000L;

    private final CatalogRepository catalogRepository;

    public CatalogInitializer(CatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    @PostConstruct
    void seedInitialCatalog() {
        if (catalogRepository.latestGeneration() > 0) {
            return;
        }
        catalogRepository.insertGeneration(new CatalogRepository.GenerationRow(
                1L, null, null, null));
        catalogRepository.insertEntry(new CatalogRepository.EntryRow(
                1L, Purpose.RESEARCH,
                new LongRange(INITIAL_RANGE_START, INITIAL_RANGE_END), null, "ACTIVE"));
        catalogRepository.insertEntry(new CatalogRepository.EntryRow(
                1L, Purpose.PERSONALIZATION,
                new LongRange(INITIAL_RANGE_START, INITIAL_RANGE_END), null, "ACTIVE"));
    }
}
