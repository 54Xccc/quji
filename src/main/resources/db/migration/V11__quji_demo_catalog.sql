-- Demo schedule is relative to the first launch of an empty environment.
INSERT INTO tb_shop
  (name,type_id,images,area,address,x,y,sold,comments,score,event_category,venue_name,event_description)
VALUES
  ('湖边的阅读交换会',0,'','西湖区','杭州植物园北门集合',120.1223,30.2556,0,0,0,
   '阅读交流','杭州植物园','带一本读过的书来交换，分享一个喜欢的片段。现场留出自由阅读时间。'),
  ('运河边的陶艺工作坊',0,'','拱墅区','桥西历史街区入口集合',120.1366,30.3190,0,0,0,
   '手作体验','桥西手作空间','从揉泥到拉坯，做出自己的第一只陶杯。零基础也可以参加。'),
  ('九溪溪谷轻徒步',0,'','西湖区','九溪公交站集合',120.1260,30.1861,0,0,0,
   '城市户外','九溪烟树','沿溪谷走一段轻量路线，途中休息拍照。请穿防滑鞋并自备饮水。'),
  ('街角的独立插画展',0,'','上城区','南宋御街入口集合',120.1695,30.2414,0,0,0,
   '展览演出','御街展览空间','走进青年插画师的纸上世界，参加一次小型导览。');

INSERT INTO tb_voucher
  (shop_id,title,rules,pay_value,actual_value,type,status,session_starts_at,session_ends_at)
SELECT id,'周末体验场','每人限报一位，入场时出示入场码。',0,0,1,1,
       UTC_TIMESTAMP() + INTERVAL 3 DAY, UTC_TIMESTAMP() + INTERVAL 74 HOUR
FROM tb_shop WHERE event_category IS NOT NULL;

INSERT INTO tb_voucher
  (shop_id,title,rules,pay_value,actual_value,type,status,session_starts_at,session_ends_at)
SELECT id,'互动演示场','用于体验从报名到入场核验的完整流程。',0,0,1,1,
       UTC_TIMESTAMP() + INTERVAL 25 MINUTE, UTC_TIMESTAMP() + INTERVAL 2 HOUR
FROM tb_shop WHERE event_category='手作体验';

INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time)
SELECT id, CASE WHEN title='互动演示场' THEN 2 ELSE 30 END,
       NOW() - INTERVAL 1 MINUTE,
       NOW() + INTERVAL (CASE WHEN title='互动演示场' THEN 15 ELSE 4200 END) MINUTE
FROM tb_voucher WHERE session_starts_at IS NOT NULL;

INSERT INTO tb_seckill_activity(voucher_id,total_stock)
SELECT s.voucher_id,s.stock FROM tb_seckill_voucher s
JOIN tb_voucher v ON v.id=s.voucher_id WHERE v.session_starts_at IS NOT NULL;

INSERT INTO tb_business_outbox(kind,aggregate_id)
SELECT 'SECKILL_PUBLISH',id FROM tb_voucher WHERE session_starts_at IS NOT NULL;
