package com.counterline.pos.orders;

import java.math.BigDecimal;
import java.time.Instant;

/** Query projection for customer analytics. */
record CustomerOrderStats(long orderCount, BigDecimal orderTotal, Instant lastOrderAt) {
}
