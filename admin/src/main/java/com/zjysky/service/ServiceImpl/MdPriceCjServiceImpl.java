package com.zjysky.service.ServiceImpl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zjysky.common.database.entity.MdPriceCj;
import com.zjysky.common.database.mapper.MdPriceCjMapper;
import com.zjysky.service.MdPriceCjService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class MdPriceCjServiceImpl extends ServiceImpl<MdPriceCjMapper, MdPriceCj> implements MdPriceCjService {

    @Override
    public List<MdPriceCj> getTodayData(LocalDate date) {
        // 调用 Mapper 中我们在上一个回答里定义的方法
        return this.baseMapper.findTodayData(date);
    }
}