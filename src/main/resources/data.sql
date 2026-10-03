-- 合成引航员数据：等级 3 最高，1 最低
INSERT INTO pilot (pilot_code, pilot_name, qualification_grade, active)
SELECT 'P001', '张伟', 3, TRUE WHERE NOT EXISTS (SELECT 1 FROM pilot WHERE pilot_code = 'P001');
INSERT INTO pilot (pilot_code, pilot_name, qualification_grade, active)
SELECT 'P002', '李强', 2, TRUE WHERE NOT EXISTS (SELECT 1 FROM pilot WHERE pilot_code = 'P002');
INSERT INTO pilot (pilot_code, pilot_name, qualification_grade, active)
SELECT 'P003', '王磊', 1, TRUE WHERE NOT EXISTS (SELECT 1 FROM pilot WHERE pilot_code = 'P003');