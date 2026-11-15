package com.hmdp.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface EventMapper {
  List<Map<String, Object>> myRegistrations(
      @Param("page") Page<?> page, @Param("owner") long owner);

  int checkIn(
      @Param("code") String code,
      @Param("operator") long operator,
      @Param("now") LocalDateTime now);
}
