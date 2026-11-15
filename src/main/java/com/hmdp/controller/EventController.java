package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IEventService;
import com.hmdp.service.ISeckillService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class EventController {
  private final IEventService events;
  private final IVoucherOrderService orders;
  private final ISeckillService reservations;
  private final IBlogService notes;

  @GetMapping("/events")
  public Result<?> catalog(
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String category,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) Double x,
      @RequestParam(required = false) Double y) {
    return events.catalog(keyword, category, page, x, y);
  }

  @GetMapping("/events/categories")
  public Result<?> categories() {
    return Result.ok(events.categories());
  }

  @GetMapping("/events/{id}")
  public Result<?> detail(@PathVariable long id) {
    return events.detail(id);
  }

  @GetMapping("/events/{id}/sessions")
  public Result<?> sessions(@PathVariable long id) {
    return events.sessions(id);
  }

  @GetMapping("/events/ranking")
  public Result<?> ranking(@RequestParam(defaultValue = "60") int windowMinutes) {
    return events.ranking(windowMinutes);
  }

  @GetMapping("/events/notes")
  public Result<?> notes(
      @RequestParam(required = false) Long eventId, @RequestParam(defaultValue = "1") int page) {
    return notes.queryEventNotes(eventId, page);
  }

  @PostMapping("/events/{id}/notes")
  public Result<?> publishNote(@PathVariable long id, @RequestBody Blog note) {
    if (!events.exists(id) || note.getContent() == null || note.getContent().trim().isEmpty()) {
      return Result.fail("请选择活动并填写体验内容");
    }
    return notes.saveBlog(note.setShopId(id));
  }

  @GetMapping("/event-feed")
  public Result<?> followFeed(
      @RequestParam long lastId, @RequestParam(defaultValue = "0") int offset) {
    return notes.queryEventNotesOfFollow(lastId, offset);
  }

  @PostMapping("/registrations/session/{id}")
  public Result<?> register(@PathVariable long id) {
    return events.isSession(id) ? orders.seckillVoucher(id) : Result.fail("报名场次不存在");
  }

  @GetMapping("/registrations/requests/{id}")
  public Result<?> registrationStatus(@PathVariable long id) {
    return reservations.queryReservationStatus(id, UserHolder.getUser().getId());
  }

  @GetMapping("/registrations")
  public Result<?> registrations(@RequestParam(defaultValue = "1") int page) {
    return events.registrations(page, UserHolder.getUser().getId());
  }

  @GetMapping("/event-admin/access")
  public Result<?> adminAccess() {
    return Result.ok();
  }

  @PostMapping("/event-admin/events")
  public Result<?> create(@RequestBody Shop event) {
    return events.saveEvent(event, null);
  }

  @PutMapping("/event-admin/events/{id}")
  public Result<?> update(@PathVariable long id, @RequestBody Shop event) {
    return events.saveEvent(event, id);
  }

  @PostMapping("/event-admin/events/{id}/sessions")
  public Result<?> publishSession(@PathVariable long id, @RequestBody Voucher session) {
    return events.publishSession(id, session);
  }

  @PostMapping("/event-admin/check-in")
  public Result<?> checkIn(@RequestBody Map<String, String> payload) {
    return events.checkIn(payload.get("code"), UserHolder.getUser().getId());
  }
}
