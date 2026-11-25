package generated.shop142;

import static io.quartetqe.runtime.Act.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.quartetqe.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Story("SHOP-142")
@ExtendWith(AgentExtension.class)
class CheckoutReservesStockTest {

    private static final String SKU_STOCK = "Read the current stock of SKU-1001";

    private ActResult placeOrder(TestAgent agent) {
        return agent.act("Place an order for 2 units of SKU-1001 for customer C-42",
                why("A customer checking out is the start of the reservation journey"),
                on("orders-api"),
                returns("orderId", "status", "durationMs"));
    }

    private int stock(TestAgent agent, String instruction) {
        return agent.act(instruction,
                why("Stock levels show whether the reservation really happened"),
                on("catalog-db"),
                returns("stock")).getInt("stock");
    }

    private long ordersCreated(TestAgent agent) {
        return agent.act("Read the orders created metric counter",
                why("The business tracks order volume through this counter"),
                on("orders-api"),
                returns("orders_created_total")).json("orders_created_total").asLong();
    }

    @Test
    @WalkingSkeleton
    @AcceptanceCriteria({"AC1", "AC2"})
    void orderFlowsThroughApiDatabaseAndEvents(TestAgent agent) {
        ActResult order = placeOrder(agent);
        assertThat(order.get("status")).isEqualTo("RESERVED");

        ActResult saved = agent.act("Find order {{orderId}} in the orders database",
                with("orderId", order.get("orderId")),
                why("A reserved order must survive beyond the request"),
                on("orders-db"),
                returns("status", "customer_id"));
        assertThat(saved.get("status")).isEqualTo("RESERVED");
        assertThat(saved.get("customer_id")).isEqualTo("C-42");

        ActResult event = agent.act("Find the order created event for order {{orderId}}",
                with("orderId", order.get("orderId")),
                why("Downstream teams rely on this event to fulfil the order"),
                on("order-events"),
                returns("found", "schemaValid"));
        assertThat(event.getBool("found")).isTrue();
        assertThat(event.getBool("schemaValid")).isTrue();
        assertThat(agent.trace().reached("order-events"))
                .as("The order event carries this test's trace, so the service passed the transaction on")
                .isTrue();
    }

    @Test
    @AcceptanceCriteria("AC3")
    void orderReducesStockByOrderedQuantity(TestAgent agent) {
        int before = stock(agent, SKU_STOCK);
        placeOrder(agent);
        assertThat(stock(agent, SKU_STOCK)).isEqualTo(before - 2);
    }

    @Test
    @AcceptanceCriteria("AC4")
    void orderBeyondAvailableStockIsRejected(TestAgent agent) {
        String lowStock = "Read the current stock of SKU-LOW";
        int before = stock(agent, lowStock);
        ActResult rejected = agent.act("Try to order 5 units of SKU-LOW for customer C-42, which exceeds available stock",
                why("Customers must never be sold items that are out of stock"),
                on("orders-api"),
                returns("status", "code"));
        assertThat(rejected.getInt("status")).isEqualTo(409);
        assertThat(rejected.get("code")).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(stock(agent, lowStock)).isEqualTo(before);
    }

    @Test
    @AcceptanceCriteria("AC5")
    void settledPaymentMarksOrderPaid(TestAgent agent) {
        ActResult order = placeOrder(agent);
        agent.act("Report that payment settled for order {{orderId}}",
                with("orderId", order.get("orderId")),
                why("Settlement is how the order learns it has been paid"),
                on("order-events"),
                returns("published"));

        ActResult paid = agent.act("Wait until order {{orderId}} is shown as PAID",
                with("orderId", order.get("orderId")),
                why("Fulfilment only starts once an order is paid"),
                on("orders-api"),
                returns("status"));
        assertThat(paid.get("status")).isEqualTo("PAID");
    }

    @Test
    @AcceptanceCriteria("AC6")
    void customerSeesConfirmationInTheWebShop(TestAgent agent) {
        ActResult order = agent.act("Order 1 unit of SKU-1001 as customer C-7 through the web shop",
                why("Most customers place their orders through the web shop"),
                on("shop-web"),
                returns("orderNumber"));
        assertThat(order.get("orderNumber")).matches("ORD-[0-9]+");

        agent.verify("The page tells the customer their order is reserved",
                why("Customers need reassurance that the purchase worked"),
                on("shop-web"));
    }

    @Test
    @AcceptanceCriteria("AC7")
    void placingAnOrderIsCountedAndMeetsItsObjective(TestAgent agent) {
        long before = ordersCreated(agent);
        ActResult order = placeOrder(agent);
        assertThat(ordersCreated(agent)).isEqualTo(before + 1);
        assertThat(order.getInt("durationMs")).isLessThan(800);
    }
}
