package archfixtures.money.good;

import java.math.BigDecimal;

import com.couplefinance.shared.money.Money;

/** Allowed: BigDecimal from String, integral minor units; doubles are fine where no money is involved elsewhere. */
public class GoodMoneyUser {
    Money total;
    long amountMinor;

    BigDecimal exact() {
        return new BigDecimal("0.10");
    }
}
