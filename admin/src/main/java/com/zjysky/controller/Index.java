package com.zjysky.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Controller
@RestController
public class Index {

    @GetMapping("/")
    public String getPreviousHoliday() {
        return "招金有色数智平台后端已成功启动";
    }
}
