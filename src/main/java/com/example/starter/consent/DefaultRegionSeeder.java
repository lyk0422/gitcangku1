package com.example.starter.consent;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动数据种子：幂等登记预置 DEFAULT 驻留区域，供未显式携带区域路由的历史调用方缺省使用。
 * 合成数据，不包含任何真实主体信息；重复执行（并发启动或重启）不报错、不产生重复行。
 */
@Component
@Order(0)
public class DefaultRegionSeeder implements ApplicationRunner {

    private static final String SEED_REQUEST_ID = "seed-default-region";

    private final RegionRepository regionRepository;

    public DefaultRegionSeeder(RegionRepository regionRepository) {
        this.regionRepository = regionRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (regionRepository.find(ConsentService.DEFAULT_REGION).isPresent()) {
            return;
        }
        try {
            regionRepository.insert(ConsentService.DEFAULT_REGION, "默认驻留区域（合成种子）", SEED_REQUEST_ID);
        } catch (DuplicateKeyException alreadyRegistered) {
            // 并发启动时另一实例已登记，忽略
        }
    }
}
