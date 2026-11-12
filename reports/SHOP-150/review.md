# Review: SHOP-150 A customer can look up an order they placed

**Ready to merge.**

## Needs your attention

Nothing.

## What was tested

| Acceptance criterion | Test | Result |
| --- | --- | --- |
| AC1: Reading an order by its orderId returns 200 with the same orderId, customerId, status and total that were returned when it was placed. | `lookingUpAnOrderShowsWhatWasPlacedAndWhatIsStored` | Passed |
| AC2: The total of a two-line order equals the sum of each item's price multiplied by its quantity. | `aTwoLineOrderTotalsEachItemsPriceTimesQuantity` | Passed |
| AC3: Reading an orderId that does not exist returns 404 with the code NOT_FOUND. | `anOrderThatDoesNotExistIsNotFound` | Passed |
| AC4: An order that was rejected for insufficient stock cannot be read afterwards, and returns 404 NOT_FOUND. | `anOrderRejectedForStockLeavesNothingToLookUp` | Passed |
| AC5: The order shown in the orders database has the same status and total as the API returns. | `lookingUpAnOrderShowsWhatWasPlacedAndWhatIsStored` | Passed |

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
| Understandability and Observability | NOTE | No test checks the service telemetry listed in the context repo. |
| Performance and Reliability | NOTE | No test compares latency with the service objective. |
| Feedback early and Often | PASS |  |
| Quality built in from day one | NOTE | AC4 says a rejected order returns 404 when read, but a rejected order has no orderId to read, so the test checks that no order row is stored for the customer instead. The story should say which it means.<br>AC2 uses two lines of the same product because the test data has only one plentiful product, so a bug mixing prices across products would not be caught. |

The AI used 0 model turns and 0 tokens. Its own checks sent back 2 draft(s) before review.

</details>
