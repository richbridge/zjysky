package com.zjysky.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zjysky.common.database.entity.MdPriceCj;

import java.time.LocalDate;
import java.util.List;

public interface MdPriceCjService extends IService<MdPriceCj> {
    // 自定义的方法
    List<MdPriceCj> getTodayData(LocalDate date);
}