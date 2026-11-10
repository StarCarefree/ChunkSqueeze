package cn.star.chunksqueeze;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandLanguageTest {
    @Test
    void currentLanguageUsesJvmZoneAndRequiresExactlyPositiveEightHours() {
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"Asia/Shanghai", "GMT+08:00"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals(CommandLanguage.CHINESE, CommandLanguage.current(), zone);
            }
            for (String zone : new String[]{"UTC", "GMT-08:00", "GMT+08:30", "Asia/Tokyo"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals(CommandLanguage.ENGLISH, CommandLanguage.current(), zone);
            }
        } finally { TimeZone.setDefault(previous); }
    }

    @Test
    void daylightSavingUsesActualOffsetRatherThanStandardOffsetOrZoneName() {
        ZoneId perth = ZoneId.of("Australia/Perth");
        assertEquals(CommandLanguage.ENGLISH, CommandLanguage.at(perth, Instant.parse("2007-01-15T00:00:00Z")));
        assertEquals(CommandLanguage.CHINESE, CommandLanguage.at(perth, Instant.parse("2007-07-15T00:00:00Z")));
    }
}
