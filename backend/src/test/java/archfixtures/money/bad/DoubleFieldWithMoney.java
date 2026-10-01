package archfixtures.money.bad;

import com.couplefinance.shared.money.Money;

/** Violation: double field in a class using Money. */
public class DoubleFieldWithMoney {
    Money total;
    double rate;
}
