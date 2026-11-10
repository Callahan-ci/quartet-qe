# SHOP-142: Checkout reserves stock and confirms the order

As a customer I want my items reserved when I check out, so that I am never sold something that is out of stock.

## Acceptance criteria

- AC1: A valid order for in-stock items returns 201 with status RESERVED and is persisted in the orders database.
- AC2: The order is published on shop.orders.created with the matching orderId and conforms to the event schema.
- AC3: The reserved quantity is decremented from catalog stock.
- AC4: An order exceeding available stock is rejected with 409 INSUFFICIENT_STOCK and stock does not change.
- AC5: When shop.payments.settled arrives for an order, its status changes to PAID.
- AC6: A customer ordering in the web shop is shown a confirmation with the order number.
- AC7: Placing an order increments orders_created_total and responds within the 800 ms objective.
