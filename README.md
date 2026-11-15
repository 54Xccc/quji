# 趣集 · 同城活动报名与交流平台

趣集是一个 Java 同城活动平台。以杭州的手作工作坊、阅读交流、轻徒步和展览为示例，用户可以找附近的活动、报名固定场次、在现场出示入场码，并分享活动体验。

## 本地启动

需要 Docker Desktop 或带 Compose v2 的 Docker。首次启动会拉取镜像、下载 Maven 依赖，并初始化数据库和两个 Kafka Topic。

```bash
docker compose -p quji -f dev/docker-compose.yml up -d
docker compose -p quji -f dev/docker-compose.yml logs -f java
```

打开 [趣集首页](http://127.0.0.1:8080/)。API 默认在 [8081 端口](http://127.0.0.1:8081/actuator/health)，网页通过 Nginx 的 `/api` 访问。Docker 卷持久化数据库、Redis、消息队列和上传图片。停止服务可执行 `docker compose -p quji -f dev/docker-compose.yml down`。

1. 在首页搜索活动，或授权浏览器定位后浏览 10 公里内的活动。用于演示的场次按数据库首次迁移时间生成。
2. 用演示手机号登录。点击获取验证码后，从 `java` 服务日志读取六位验证码。
3. 在活动详情里选择场次并报名。在「我的报名」查看处理进度，报名成功后复制入场码。
4. 授权管理账号后，打开「活动管理」发布活动或核验入场；在已有活动详情页可以直接进入该活动的管理界面。入场窗口为活动开始前 30 分钟至活动结束。
5. 在活动详情进入「活动体验」，发布图文体验、点赞、关注作者。在「活动交流」切换最新体验和关注动态。

### 设置活动管理员

用准备管理活动的手机号登录后，从浏览器网络面板的 `GET /api/user/me` 响应里找到 `data.id`。在项目根目录创建 `.env`，设置 `HMDP_ADMIN_IDS` 为该 ID；多个 ID 用逗号分隔。执行 `docker compose -p quji -f dev/docker-compose.yml up -d java` 并刷新页面。上传文件和数据库继续保存在原有卷中。

首次启动后的「互动演示场」可直接验证入场，开放报名约 15 分钟。之后可用管理页创建新场次，将活动开始设为当前时间后约 20 分钟，并在开始前设置报名截止时间。

## 技术与业务

| 业务 | 实现 |
| --- | --- |
| 活动浏览与变更 | Spring Boot、MyBatis-Plus、MySQL、Flyway；详情沿用请求内缓存、Caffeine、Memcached、Redis 和 MySQL；Outbox 与 Canal 处理缓存失效 |
| 附近活动 | Redis GEO 在 10 公里内查找并按距离排序 |
| 限额报名 | Redis Lua 原子校验窗口、用户资格与库存；Redis Stream 接力；Kafka `QujiBusiness` Topic 异步确认，MySQL 条件扣减库存；Redisson、唯一约束、重试和补偿避免重复履约 |
| 入场核验 | 每个已确认报名生成独立入场码；管理接口在入场窗口内一次性写入核验状态、时间和操作人 |
| 活动体验 | Vue 2 页面；图片归属校验、点赞、关注和异步 Feed；Redis Bitmap 记录每日打卡 |
| 活动热榜 | Kafka `HmdpShopBehavior` Topic 汇总活动浏览和报名事件；按事件 ID 去重，聚合近 5 或 60 分钟的数据 |
| 运行与观察 | Nginx、Docker Compose；Actuator、Prometheus 和 Alertmanager；首页底部的「技术体验」可观察原有技术链路 |

活动在课程目录表中拥有独立分类与场地字段，场次包含报名时间、活动时间与容量。数据库用增量迁移添加新字段并写入趣集演示数据。活动前台仅展示趣集活动，历史 API 和订单迁移路径可继续用于回归与技术演示。

当前趣集场次为免费报名、固定时间、每人每场一位。管理页支持创建与编辑活动、发布场次和核验入场。现有普通订单的模拟支付和超时关单仍可在原有技术演示路径运行。体验环境从服务端日志获取验证码。

### 从 RocketMQ 版本升级

复用原来的 Compose 项目名和数据库卷。先停止旧版本的所有 Java 实例，再启动新版本。Flyway V12 会让旧 Broker 上已投递但尚未确认的 Outbox 任务重新排队，并保留事件号和 Inbox 回执。

```bash
docker compose -p quji -f dev/docker-compose.yml stop java
docker compose -p quji -f dev/docker-compose.yml up -d --remove-orphans
```

业务 Topic 的消费者在事务完成、重试计划落库或事件已隔离后提交 offset。业务失败从 MySQL Outbox 退避重投，达到上限后进入人工复核。普通订单的关单任务在截止时间到达后才投递，数据库定时扫描仍提供兜底。

## API

| 路径 | 用途 |
| --- | --- |
| `GET /events` | 按名称、分类或位置浏览活动 |
| `GET /events/{id}`、`GET /events/{id}/sessions` | 活动详情与场次 |
| `POST /registrations/session/{id}` | 申请场次名额，返回请求 ID |
| `GET /registrations/requests/{id}`、`GET /registrations` | 查询自己的报名进度和入场凭证 |
| `GET /events/notes`、`POST /events/{id}/notes` | 查看与发布活动体验 |
| `GET /event-feed`、`GET /events/ranking` | 关注动态与活动热榜 |
| `POST /event-admin/events`、`PUT /event-admin/events/{id}` | 创建或更新活动 |
| `POST /event-admin/events/{id}/sessions`、`POST /event-admin/check-in` | 发布场次与核验入场，要求管理员身份 |

场次接口中的时间为 UTC，网页按北京时间填写和展示。单独运行 Java 时将 JVM 和 MySQL 会话时区设为 UTC。Redis 首次接到报名时返回受理状态，报名是否确认以之后的状态查询为准。

## 验证

```bash
npm --prefix frontend run check
npm --prefix frontend test
docker volume create hm-dianping_maven-cache
docker compose -p quji-test -f dev/docker-compose.test.yml run --rm test
```

测试 Compose 使用独立数据库和 Redis，覆盖缓存、CDC、消息可靠性、Kafka 聚合、秒杀与补偿和原有订单行为。
