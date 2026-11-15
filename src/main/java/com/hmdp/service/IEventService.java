package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import java.util.List;

public interface IEventService {
  List<String> categories();

  Result<?> catalog(String keyword, String category, int page, Double x, Double y);

  Result<?> detail(long id);

  boolean exists(long id);

  Result<?> sessions(long eventId);

  Result<?> ranking(int windowMinutes);

  Result<?> saveEvent(Shop input, Long id);

  Result<?> publishSession(long eventId, Voucher session);

  boolean isSession(long id);

  Result<?> registrations(int page, long owner);

  Result<?> checkIn(String code, long operator);
}
