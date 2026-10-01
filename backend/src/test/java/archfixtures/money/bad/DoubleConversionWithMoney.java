package archfixtures.money.bad;

import com.couplefinance.shared.money.Money;

/** Violation: primitive double local obtained through doubleValue() in a class using Money. */
public class DoubleConversionWithMoney {
    Money held;

    long scaled(java.math.BigDecimal amount) {
        var local = amount.doubleValue() * 2;
        return (long) local;
    }
}
