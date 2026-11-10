package app.brewflow.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** numeric(12,2) money. Section 4.1: BigDecimal with HALF_UP to two decimals. */
public final class Money {

    public static final BigDecimal MAX = new BigDecimal("9999999999.99");
    private static final Pattern TWO_DECIMALS = Pattern.compile("\\d{1,10}\\.\\d{2}");

    private Money() {
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static String format(BigDecimal value) {
        return value == null ? null : round(value).toPlainString();
    }

    /** A non-negative admin-entered amount with at most two decimals. */
    public static BigDecimal requireNonNegative(String field, BigDecimal value) {
        if (value == null) {
            throw ApiException.invalid(field, field + " is required.");
        }
        if (value.signum() < 0) {
            throw ApiException.invalid(field, field + " must not be negative.");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw ApiException.invalid(field, field + " has more than two decimals.");
        }
        if (value.compareTo(MAX) > 0) {
            throw ApiException.invalid(field, field + " is too large.");
        }
        return round(value);
    }

    /** Amounts attested by staff are strings with exactly two decimals, compared character for character. */
    public static boolean isTwoDecimalString(String raw) {
        return raw != null && TWO_DECIMALS.matcher(raw).matches();
    }
}
