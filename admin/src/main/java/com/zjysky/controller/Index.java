package com.zjysky.controller;

import com.zjysky.common.utils.MicaHolidays;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;

@Controller
@RestController
public class Index {

    private MicaHolidays micaHolidays;

    public Index(MicaHolidays micaHolidays) {
        this.micaHolidays = micaHolidays;
    }

    public void HolidayController(MicaHolidays micaHolidays) {
        this.micaHolidays = micaHolidays;
    }

    @GetMapping("/")
    public String getPreviousHoliday() {

        LocalDate today = LocalDate.now();

        String fom1 = micaHolidays.getPreviousWorkdayYyyyMMdd(today);

        String fom2 = micaHolidays.getPreviousWorkdayYyyyMmDd(today);

        return fom1;
    }
}
