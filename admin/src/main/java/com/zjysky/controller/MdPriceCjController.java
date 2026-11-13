package com.zjysky.controller;

import com.zjysky.common.database.entity.MdPriceCj;
import com.zjysky.service.MdPriceCjService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/price")
public class MdPriceCjController {

    @Autowired
    private MdPriceCjService mdPriceCjService;

    /**
     * 测试您自定义的 XML 查询方法
     */
    @GetMapping("/today")
    public List<MdPriceCj> getTodayData() {
        // 传入今天的日期
        return mdPriceCjService.getTodayData(LocalDate.now());
    }

    /**
     * 测试 MyBatis-Plus 自带的强大 CRUD 功能 (无需写任何 SQL)
     */
    @GetMapping("/test-crud")
    public String testCrud() {
        // 1. 测试自带的新增 (Insert)
        MdPriceCj entity = new MdPriceCj();
        entity.setTaskType("QUOTE");
        entity.setProductId("201102250293");
        entity.setProductName("长江1#电解铜");
        entity.setQuoteDate(LocalDate.now());
        entity.setAvgPrice(new java.math.BigDecimal("111441.143"));
        // ... 设置其他字段
        mdPriceCjService.save(entity); 

        // 2. 测试自带的查询 (Select)
        MdPriceCj one = mdPriceCjService.getById(entity.getId());
        
        return "新增并查询成功，产品名称：" + one.getProductName();
    }
}