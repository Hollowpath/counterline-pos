package com.counterline.pos.orders;

/** Query projection: total quantity of one item across a set of orders. */
record ItemQuantity(String itemName, long quantity) {
}
