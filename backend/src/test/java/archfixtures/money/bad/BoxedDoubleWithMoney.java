package archfixtures.money.bad;

import com.couplefinance.shared.money.Money;

/** Violation: Double.parseDouble in a class using Money. */
public class BoxedDoubleWithMoney {
    Money held;

    long parse(String text) {
        return (long) Double.parseDouble(text);
    }
}
