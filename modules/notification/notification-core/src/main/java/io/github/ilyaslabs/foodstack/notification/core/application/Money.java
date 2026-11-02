package io.github.ilyaslabs.foodstack.notification.core.application;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Currency;
import java.util.Locale;

/** Amounts in messages: ISO 4217 minor units to text, such as {@code PKR 2,550.00}. */
final class Money {

    private Money() {
    }

    static String format(long minor, String currency) {
        int digits = 2;
        try {
            digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        }
        catch (RuntimeException ex) {
            // unknown code: two digits
        }
        DecimalFormat format = new DecimalFormat((digits == 0) ? "#,##0" : "#,##0." + "0".repeat(digits),
                DecimalFormatSymbols.getInstance(Locale.ROOT));
        return currency + " " + format.format(BigDecimal.valueOf(minor, digits));
    }
}
