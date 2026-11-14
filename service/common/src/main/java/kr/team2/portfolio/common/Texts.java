package kr.team2.portfolio.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 문구 틀 파일(UTF-8 .properties)을 읽어 채운다 (docs/03 §6.3). 추천 근거·알림 문구가 같이 쓴다.
 * 틀 안의 {@code {이름:형식}}을 값으로 바꾼다. 형식은 {@code %0 · %0.0 · +%0.0 · 0.0 · 0.00 · #,###}.
 */
public final class Texts {
    private static final Pattern SLOT = Pattern.compile("\\{(\\w+)(?::([^}]+))?}");

    private final Properties props;

    private Texts(Properties props) {
        this.props = props;
    }

    /** @param file 클래스패스의 문구 파일 (예: messages_reasons.properties) */
    public static Texts load(String file) {
        Properties p = new Properties();
        try (InputStream in = Texts.class.getClassLoader().getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException("문구 파일이 없어요: " + file);
            }
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Texts(p);
    }

    /** 틀 그대로. 없으면 예외 (오타를 빨리 찾으려고). */
    public String raw(String key) {
        String t = props.getProperty(key);
        if (t == null) {
            throw new IllegalArgumentException("문구 키가 없어요: " + key);
        }
        return t;
    }

    public boolean has(String key) {
        return props.containsKey(key);
    }

    /** 키가 없으면 대신 쓸 값. 라벨처럼 없어도 되는 것에 쓴다. */
    public String rawOr(String key, String fallback) {
        return props.getProperty(key, fallback);
    }

    public String text(String key, Map<String, ?> vars) {
        return fill(raw(key), vars);
    }

    public String text(String key) {
        return raw(key);
    }

    public static String fill(String template, Map<String, ?> vars) {
        Matcher m = SLOT.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            if (!vars.containsKey(name)) {
                throw new IllegalArgumentException("문구 값이 없어요: " + name + " in " + template);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(format(vars.get(name), m.group(2))));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static String format(Object value, String spec) {
        if (value == null) {
            return "-";
        }
        if (spec == null) {
            if (value instanceof Double d && d == Math.rint(d)) {
                return String.valueOf(d.longValue());   // 7.0년 → 7년
            }
            return String.valueOf(value);
        }
        BigDecimal v = BigDecimal.valueOf(((Number) value).doubleValue());
        return switch (spec) {
            case "%0" -> percent(v, 0, false);
            case "%0.0" -> percent(v, 1, false);
            case "+%0.0" -> percent(v, 1, true);
            case "0.0" -> decimal(v, 1);
            case "0.00" -> decimal(v, 2);
            case "#,###" -> new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT))
                    .format(v.setScale(0, RoundingMode.HALF_UP));
            default -> throw new IllegalArgumentException("모르는 형식: " + spec);
        };
    }

    private static String percent(BigDecimal v, int scale, boolean sign) {
        BigDecimal p = v.movePointRight(2).setScale(scale, RoundingMode.HALF_UP);
        if (p.signum() == 0) {
            p = p.abs();                    // -0% 방지
        }
        String s = p.toPlainString() + "%";
        return sign && p.signum() >= 0 ? "+" + s : s;
    }

    private static String decimal(BigDecimal v, int scale) {
        BigDecimal p = v.setScale(scale, RoundingMode.HALF_UP);
        return (p.signum() == 0 ? p.abs() : p).toPlainString();
    }
}
