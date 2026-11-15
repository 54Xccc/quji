package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.EventMapper;
import com.hmdp.service.IBehaviorJournalService;
import com.hmdp.service.IEventService;
import com.hmdp.service.IShopHeatService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.UserHolder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventService implements IEventService {
  private static final int PAGE_SIZE = 12;
  private static final String GEO_KEY = "quji:events:geo";
  private static final DefaultRedisScript<Long> BUILD_GEO =
      new DefaultRedisScript<>(
          "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end "
              + "for i=1,#ARGV,3 do redis.call('GEOADD',KEYS[1],ARGV[i],ARGV[i+1],ARGV[i+2]) end "
              + "return redis.call('EXPIRE',KEYS[1],30)",
          Long.class);

  private final IShopService shops;
  private final IVoucherService vouchers;
  private final IShopHeatService heat;
  private final IBehaviorJournalService behavior;
  private final EventMapper events;
  private final StringRedisTemplate redis;
  private final CacheClient cache;

  private static final List<String> CATEGORIES =
      Collections.unmodifiableList(Arrays.asList("手作体验", "城市户外", "阅读交流", "展览演出"));

  @Override
  public List<String> categories() {
    return CATEGORIES;
  }

  @Override
  public Result<?> catalog(String keyword, String category, int page, Double x, Double y) {
    if (page < 1
        || page > 1000
        || !textFits(keyword, 60)
        || (!category.isEmpty() && !CATEGORIES.contains(category))
        || ((x != null || y != null) && !validCoordinates(x, y))) {
      return Result.fail("活动筛选条件无效");
    }
    QueryWrapper<Shop> filter =
        eventFilter()
            .eq(!category.isEmpty(), "event_category", category)
            .like(!keyword.trim().isEmpty(), "name", keyword.trim());
    if (x != null) {
      return nearby(filter, page, x, y);
    }
    Page<Shop> rows = shops.page(new Page<>(page, PAGE_SIZE), filter.orderByDesc("id"));
    return Result.ok(rows.getRecords(), rows.getTotal());
  }

  private Result<?> nearby(QueryWrapper<Shop> filter, int page, double x, double y) {
    if (!Boolean.TRUE.equals(redis.hasKey(GEO_KEY))) {
      List<String> args = new ArrayList<>();
      for (Shop event : shops.list(eventFilter().select("id", "x", "y"))) {
        if (validCoordinates(event.getX(), event.getY())) {
          args.add(event.getX().toString());
          args.add(event.getY().toString());
          args.add(event.getId().toString());
        }
      }
      redis.execute(BUILD_GEO, Collections.singletonList(GEO_KEY), args.toArray());
    }
    GeoResults<RedisGeoCommands.GeoLocation<String>> nearby =
        redis.opsForGeo()
            .search(
                GEO_KEY,
                GeoReference.fromCoordinate(x, y),
                new Distance(10, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                    .includeDistance()
                    .sortAscending());
    if (nearby == null || nearby.getContent().isEmpty()) {
      return Result.ok(Collections.emptyList(), 0L);
    }
    List<Long> ids =
        nearby.getContent().stream()
            .map(row -> Long.valueOf(row.getContent().getName()))
            .collect(Collectors.toList());
    Map<Long, Shop> found =
        shops.list(filter.in("id", ids)).stream()
            .collect(Collectors.toMap(Shop::getId, Function.identity()));
    List<Shop> result = new ArrayList<>();
    nearby.forEach(
        row -> {
          Shop event = found.get(Long.valueOf(row.getContent().getName()));
          if (event != null) {
            event.setDistance(row.getDistance().getValue() * 1000);
            result.add(event);
          }
        });
    int from = Math.min((page - 1) * PAGE_SIZE, result.size());
    return Result.ok(result.subList(from, Math.min(from + PAGE_SIZE, result.size())),
        (long) result.size());
  }

  @Override
  public Result<?> detail(long id) {
    Shop event = (Shop) shops.queryById(id).getData();
    if (event == null || event.getEventCategory() == null) {
      return Result.fail("活动不存在");
    }
    Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
    behavior.recordShopVisit(id, userId);
    return Result.ok(event);
  }

  @Override
  public boolean exists(long id) {
    Shop event = (Shop) shops.queryById(id).getData();
    return event != null && event.getEventCategory() != null;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Result<?> sessions(long eventId) {
    if (!exists(eventId)) {
      return Result.fail("活动不存在");
    }
    Result<List<Voucher>> list = vouchers.queryVoucherOfShop(eventId);
    return Result.ok(list.getData().stream()
        .filter(voucher -> voucher.getSessionStartsAt() != null)
        .sorted(Comparator.comparing(Voucher::getSessionStartsAt))
        .collect(Collectors.toList()));
  }

  @SuppressWarnings("unchecked")
  @Override
  public Result<?> ranking(int windowMinutes) {
    Result<List<Map<String, Object>>> result = heat.ranking(windowMinutes, 100);
    if (!Boolean.TRUE.equals(result.getSuccess())) {
      return result;
    }
    List<Long> ids = result.getData().stream()
        .map(row -> ((Number) row.get("shop_id")).longValue())
        .collect(Collectors.toList());
    if (ids.isEmpty()) {
      return Result.ok(Collections.emptyList());
    }
    List<Long> eventIds = shops.list(eventFilter().select("id").in("id", ids))
        .stream().map(Shop::getId).collect(Collectors.toList());
    return Result.ok(result.getData().stream()
        .filter(row -> eventIds.contains(((Number) row.get("shop_id")).longValue()))
        .limit(10).collect(Collectors.toList()));
  }

  @Override
  public Result<?> saveEvent(Shop input, Long id) {
    if (!CATEGORIES.contains(input.getEventCategory())
        || !required(input.getName(), 128)
        || !required(input.getVenueName(), 128)
        || !required(input.getAddress(), 255)
        || !required(input.getEventDescription(), 2000)
        || !validCoordinates(input.getX(), input.getY())) {
      return Result.fail("请填写活动名称、分类、场地、地址、介绍和有效坐标");
    }
    Shop event = new Shop().setId(id)
        .setName(input.getName().trim())
        .setEventCategory(input.getEventCategory())
        .setVenueName(input.getVenueName().trim())
        .setAddress(input.getAddress().trim())
        .setX(input.getX()).setY(input.getY())
        .setEventDescription(input.getEventDescription().trim());
    if (id == null) {
      event.setTypeId(0L).setImages("").setSold(0).setComments(0).setScore(0);
      return shops.save(event) ? Result.ok(event.getId()) : Result.fail("活动保存失败");
    }
    return exists(id) ? shops.update(event) : Result.fail("活动不存在");
  }

  @Override
  public Result<?> publishSession(long eventId, Voucher session) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    if (!exists(eventId)
        || !required(session.getTitle(), 100)
        || !required(session.getRules(), 1000)
        || session.getStock() == null
        || session.getStock() < 1
        || session.getStock() > 100000
        || session.getBeginTime() == null
        || session.getEndTime() == null
        || session.getSessionStartsAt() == null
        || session.getSessionEndsAt() == null
        || !session.getEndTime().isAfter(now)
        || !session.getBeginTime().isAfter(now.minusDays(1))
        || !session.getBeginTime().isBefore(session.getEndTime())
        || !session.getSessionStartsAt().isAfter(session.getEndTime())
        || !session.getSessionEndsAt().isAfter(session.getSessionStartsAt())
        || session.getSessionEndsAt().isAfter(now.plusYears(2))) {
      return Result.fail("请检查场次名称、容量和报名及活动时间");
    }
    Voucher freeSession =
        new Voucher()
            .setShopId(eventId)
            .setTitle(session.getTitle().trim())
            .setRules(session.getRules().trim())
            .setStock(session.getStock())
            .setBeginTime(session.getBeginTime())
            .setEndTime(session.getEndTime())
            .setSessionStartsAt(session.getSessionStartsAt())
            .setSessionEndsAt(session.getSessionEndsAt())
            .setType(1)
            .setStatus(1)
            .setPayValue(0L)
            .setActualValue(0L);
    vouchers.addSeckillVoucher(freeSession);
    return Result.ok(freeSession.getId());
  }

  @Override
  public boolean isSession(long id) {
    Voucher session =
        cache.queryWithMutex(
            "cache:voucher-meta:", id, Voucher.class, vouchers::getById, 10L, TimeUnit.MINUTES);
    return session != null && session.getSessionStartsAt() != null;
  }

  @Override
  public Result<?> registrations(int page, long owner) {
    if (page < 1 || page > 1000) {
      return Result.fail("分页参数无效");
    }
    Page<Map<String, Object>> paging = new Page<>(page, PAGE_SIZE);
    return Result.ok(events.myRegistrations(paging, owner), paging.getTotal());
  }

  @Override
  public Result<?> checkIn(String code, long operator) {
    if (code == null || !code.trim().matches("[a-fA-F0-9-]{36}")) {
      return Result.fail("请输入完整的入场码");
    }
    boolean updated = events.checkIn(code.trim(), operator, LocalDateTime.now(ZoneOffset.UTC)) == 1;
    return updated ? Result.ok() : Result.fail("入场码无效、已核验，或当前不在入场时段");
  }

  private static QueryWrapper<Shop> eventFilter() {
    return new QueryWrapper<Shop>().isNotNull("event_category");
  }

  private static boolean validCoordinates(Double x, Double y) {
    return x != null && y != null && x > 0 && x <= 180 && y > 0 && y <= 85;
  }

  private static boolean textFits(String value, int max) {
    return value != null && value.length() <= max;
  }

  private static boolean required(String value, int max) {
    return textFits(value, max) && !value.trim().isEmpty();
  }
}
