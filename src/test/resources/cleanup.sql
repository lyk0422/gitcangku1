-- 测试场景数据清理：每个用例执行前清空业务表（独立命名库 drspot_test，无外键，顺序无关）。
DELETE FROM ledger_entry;
DELETE FROM settlement;
DELETE FROM meter_reading;
DELETE FROM commitment;
DELETE FROM invitation;
