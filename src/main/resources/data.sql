-- 合成基础数据：1 个机场、2 辆除冰车、3 个航班。
INSERT INTO airport (code, name) SELECT 'ZTST', '合成测试机场' WHERE NOT EXISTS (SELECT 1 FROM airport);
INSERT INTO deicing_vehicle (airport_id, code) SELECT id, 'DEICE-01' FROM airport WHERE NOT EXISTS (SELECT 1 FROM deicing_vehicle);
INSERT INTO deicing_vehicle (airport_id, code) SELECT id, 'DEICE-02' FROM airport WHERE NOT EXISTS (SELECT 1 FROM deicing_vehicle WHERE code = 'DEICE-02');
INSERT INTO flight (airport_id, callsign) SELECT id, 'SYN1001' FROM airport WHERE NOT EXISTS (SELECT 1 FROM flight);
INSERT INTO flight (airport_id, callsign) SELECT id, 'SYN1002' FROM airport WHERE NOT EXISTS (SELECT 1 FROM flight WHERE callsign = 'SYN1002');
INSERT INTO flight (airport_id, callsign) SELECT id, 'SYN1003' FROM airport WHERE NOT EXISTS (SELECT 1 FROM flight WHERE callsign = 'SYN1003');