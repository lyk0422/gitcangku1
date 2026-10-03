package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.repo.PilotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 默认配置冒烟：不覆盖数据源，验证本地入口使用的 H2 内存库（MODE=MySQL）可启动、
 * 自动建表并初始化合成引航员数据。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class ApplicationContextTest {

    @Autowired
    private PilotRepository pilotRepo;

    @Test
    void contextStartsWithDefaultH2AndSeedsPilots() {
        var pilot = pilotRepo.findByPilotCode("P001");
        assertTrue(pilot.isPresent());
        assertEquals(3, pilot.get().qualificationGrade());
        assertTrue(pilotRepo.findByPilotCode("P003").isPresent());
    }
}