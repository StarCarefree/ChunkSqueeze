package cn.star.chunksqueeze;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

public enum CommandLanguage {
    CHINESE, ENGLISH;

    public static CommandLanguage current() {
        return at(ZoneId.systemDefault(), Instant.now());
    }

    static CommandLanguage at(ZoneId zone, Instant instant) {
        return zone.getRules().getOffset(instant).getTotalSeconds() == 8 * 3600 ? CHINESE : ENGLISH;
    }

    public String text(String chinese, String english, Object... arguments) {
        String template = this == CHINESE ? chinese : english;
        return arguments.length == 0 ? template : String.format(Locale.ROOT, template, arguments);
    }

    public String flag(boolean value) {
        return text(value ? "是" : "否", Boolean.toString(value));
    }

    public static String message(String chinese, String english, Object... arguments) {
        return current().text(chinese, english, arguments);
    }
}
