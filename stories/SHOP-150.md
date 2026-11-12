# SHOP-150: A customer can look up an order they placed

As a customer I want to look up an order I have placed, so that I can check its status and total without contacting support.

## Acceptance criteria

- AC1: Reading an order by its orderId returns 200 with the same orderId, customerId, status and total that were returned when it was placed.
- AC2: The total of a two-line order equals the sum of each item's price multiplied by its quantity.
- AC3: Reading an orderId that does not exist returns 404 with the code NOT_FOUND.
- AC4: An order that was rejected for insufficient stock cannot be read afterwards, and returns 404 NOT_FOUND.
- AC5: The order shown in the orders database has the same status and total as the API returns.
