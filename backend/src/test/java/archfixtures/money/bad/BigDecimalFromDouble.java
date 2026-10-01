package archfixtures.money.bad;

import java.math.BigDecimal;

/** Violation: new BigDecimal(double). */
public class BigDecimalFromDouble {
    BigDecimal imprecise() {
        return new BigDecimal(0.1);
    }
}
