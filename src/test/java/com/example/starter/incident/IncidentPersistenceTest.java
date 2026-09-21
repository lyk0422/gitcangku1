package com.example.starter.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 持久化测试：模拟进程重启（关闭全部连接后重新打开同一数据库文件），
 * 验证事件状态、指挥链、处置记录与幂等记录不丢失。
 */
class IncidentPersistenceTest {

    @TempDir
    Path tempDir;

    private DataSource newDataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    @Test
    void stateSurvivesRestart() throws Exception {
        String url = "jdbc:h2:file:" + tempDir.resolve("incidentdb").toString().replace('\\', '/')
                + ";MODE=MySQL";

        // 第一次“进程”：建表并写入一条完整指挥链
        DataSource first = newDataSource(url);
        JdbcTemplate jdbc1 = new JdbcTemplate(first);
        try (var connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        IncidentRepository incidents1 = new IncidentRepository(jdbc1);
        IncidentActionRepository actions1 = new IncidentActionRepository(jdbc1);
        CommandRecordRepository commands1 = new CommandRecordRepository(jdbc1);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Incident saved = incidents1.insert(new Incident(
                null, "INC-PERSIST", Severity.S1, "支付网关超时", "ops-bot",
                IncidentStatus.REPORTED, null, null, 0, now, now));
        incidents1.update(new Incident(
                saved.id(), saved.incidentKey(), saved.severity(), saved.summary(), saved.reporter(),
                IncidentStatus.COMMANDING, "alice", "bob", 1, saved.createdAt(), now));
        actions1.insert(new IncidentAction(
                null, saved.id(), "act-1", "alice", now, "MITIGATE", "切换备用网关", now));
        commands1.insert(new CommandRecord(
                null, saved.id(), "cmd-take", "TAKE_COMMAND", "alice", 200, "{}", now));

        // 第二次“进程”：全新 DataSource/JdbcTemplate 读取同一文件
        DataSource second = newDataSource(url);
        JdbcTemplate jdbc2 = new JdbcTemplate(second);
        IncidentRepository incidents2 = new IncidentRepository(jdbc2);
        IncidentActionRepository actions2 = new IncidentActionRepository(jdbc2);
        CommandRecordRepository commands2 = new CommandRecordRepository(jdbc2);

        Incident reloaded = incidents2.findByKey("INC-PERSIST").orElseThrow();
        assertEquals(IncidentStatus.COMMANDING, reloaded.status());
        assertEquals("alice", reloaded.commanderId());
        assertEquals("bob", reloaded.pendingCommanderId());
        assertEquals(1, reloaded.version());

        var reloadedActions = actions2.findByIncidentId(reloaded.id());
        assertEquals(1, reloadedActions.size());
        assertEquals("act-1", reloadedActions.get(0).actionKey());
        assertEquals("alice", reloadedActions.get(0).actorId());
        assertEquals(now, reloadedActions.get(0).occurredAt());

        assertTrue(commands2.find(reloaded.id(), "cmd-take").isPresent());
        assertEquals("alice", commands2.find(reloaded.id(), "cmd-take").orElseThrow().fingerprint());
    }
}
