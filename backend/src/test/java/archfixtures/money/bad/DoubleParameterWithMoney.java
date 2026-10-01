package archfixtures.money.bad;

import com.couplefinance.shared.money.Money;

/** Violation: floating point parameter in a class using Money. */
public class DoubleParameterWithMoney {
    Money scale(Money money, double factor) {
        return money;
    }
}
