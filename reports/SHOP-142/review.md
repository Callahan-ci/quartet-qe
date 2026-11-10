# Review: SHOP-142 Checkout reserves stock and confirms the order

**Ready to merge.**

## Needs your attention

Nothing.

## What was tested

| Acceptance criterion | Test | Result |
| --- | --- | --- |
| AC1: A valid order for in-stock items returns 201 with status RESERVED and is persisted in the orders database. | `orderFlowsThroughApiDatabaseAndEvents` | Passed |
| AC2: The order is published on shop.orders.created with the matching orderId and conforms to the event schema. | `orderFlowsThroughApiDatabaseAndEvents` | Passed |
| AC3: The reserved quantity is decremented from catalog stock. | `orderReducesStockByOrderedQuantity` | Passed |
| AC4: An order exceeding available stock is rejected with 409 INSUFFICIENT_STOCK and stock does not change. | `orderBeyondAvailableStockIsRejected` | Passed |
| AC5: When shop.payments.settled arrives for an order, its status changes to PAID. | `settledPaymentMarksOrderPaid` | Passed |
| AC6: A customer ordering in the web shop is shown a confirmation with the order number. | `customerSeesConfirmationInTheWebShop` | Passed |
| AC7: Placing an order increments orders_created_total and responds within the 800 ms objective. | `placingAnOrderIsCountedAndMeetsItsObjective` | Passed |

<details>
<summary>How this was checked</summary>

| Engineering principle | Status | Notes |
| --- | --- | --- |
| Design for emergent reuse | PASS |  |
| Build evolutionary systems to enable easy incremental change | PASS |  |
| Small and simple reduce complexity ensure testable | PASS |  |
| Decouple and be cohesive as possible | PASS |  |
| Data stewardship ensure security and protection | PASS |  |
| Production ready from the start | PASS |  |
| Secure by design | PASS |  |
| Automation by default | PASS |  |
| Understandability and Observability | PASS |  |
| Performance and Reliability | PASS |  |
| Feedback early and Often | PASS |  |
| Quality built in from day one | NOTE | No test covers two customers buying the last unit at the same time. |

The AI used 13 model turns and 0 tokens. Its own checks sent back 1 draft(s) before review.

Bugs were planted to prove the tests catch them: 100% caught, 0 false alarm(s).

| Planted change | Result |
| --- | --- |
| Order is saved with the wrong status | Caught |
| Order is never written to the database | Caught |
| Order event is not published | Caught |
| Stock is not reserved | Caught |
| Orders beyond stock are accepted | Caught |
| Payment settlement is ignored | Caught |
| Web confirmation is not shown | Caught |
| Orders are not counted | Caught |
| Order placement exceeds its objective | Caught |
| Harmless redesign renames the order button | Tests still passed, as they should |

</details>
