package generated.shop150;

import static io.quartetqe.runtime.Act.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.quartetqe.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Story("SHOP-150")
@ExtendWith(AgentExtension.class)
class OrderLookupTest {

    @Test
    @WalkingSkeleton
    @AcceptanceCriteria({"AC1", "AC5"})
    void lookingUpAnOrderShowsWhatWasPlacedAndWhatIsStored(TestAgent agent) {
        ActResult placed = agent.act("Place an order for 2 units of SKU-1001 for customer C-42",
                why("Looking up an order starts from an order the customer has placed"),
                on("orders-api"),
                returns("orderId", "status", "total"));
        ActResult found = agent.act("Look up order {{orderId}}",
                with("orderId", placed.get("orderId")),
                why("A customer checks their order without contacting support"),
                on("orders-api"),
                returns("orderId", "customerId", "status", "total"));

        assertThat(found.get("orderId")).isEqualTo(placed.get("orderId"));
        assertThat(found.get("customerId")).isEqualTo("C-42");
        assertThat(found.get("status")).isEqualTo(placed.get("status"));
        assertThat(found.getDouble("total")).isEqualTo(placed.getDouble("total"));

        ActResult row = agent.act("Find order {{orderId}} in the orders database",
                with("orderId", found.get("orderId")),
                why("The stored order is the source of truth for what the API shows"),
                on("orders-db"),
                returns("status", "total"));
        assertThat(row.get("status")).isEqualTo(found.get("status"));
        assertThat(row.getDouble("total")).isEqualTo(found.getDouble("total"));
    }

    @Test
    @AcceptanceCriteria("AC2")
    void aTwoLineOrderTotalsEachItemsPriceTimesQuantity(TestAgent agent) {
        ActResult product = agent.act("Read the unit price of SKU-1001",
                why("The expected total comes from the catalog, not from the order itself"),
                on("catalog-db"),
                returns("price"));
        ActResult order = agent.act("Place an order for customer C-42 with two lines for SKU-1001: one line of 1 unit and one line of 2 units",
                why("A basket with several lines must total every line"),
                on("orders-api"),
                returns("orderId", "total"));

        assertThat(order.getDouble("total")).isCloseTo(product.getDouble("price") * 3, within(0.001));
    }

    @Test
    @AcceptanceCriteria("AC3")
    void anOrderThatDoesNotExistIsNotFound(TestAgent agent) {
        ActResult missing = agent.act("Look up order ORD-0000000000, which was never placed",
                why("A clear not-found answer tells the customer the order number is wrong"),
                on("orders-api"),
                returns("status", "code"));

        assertThat(missing.getInt("status")).isEqualTo(404);
        assertThat(missing.get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    @AcceptanceCriteria("AC4")
    void anOrderRejectedForStockLeavesNothingToLookUp(TestAgent agent) {
        ActResult before = agent.act("Count the orders stored for customer C-7",
                why("A rejected order must not leave a record behind"),
                on("orders-db"),
                returns("orders"));
        ActResult rejected = agent.act("Try to order 5 units of SKU-LOW for customer C-7, which exceeds available stock",
                why("Customers must never be sold items that are out of stock"),
                on("orders-api"),
                returns("status", "code"));
        ActResult after = agent.act("Count the orders stored for customer C-7",
                why("A rejected order must not leave a record behind"),
                on("orders-db"),
                returns("orders"));

        assertThat(rejected.getInt("status")).isEqualTo(409);
        assertThat(rejected.get("code")).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(after.getInt("orders")).isEqualTo(before.getInt("orders"));
    }
}
