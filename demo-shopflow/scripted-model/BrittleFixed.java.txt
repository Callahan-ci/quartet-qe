package generated.brittle;

import static io.quartetqe.runtime.Act.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.quartetqe.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Story("SHOP-142")
@ExtendWith(AgentExtension.class)
class BrittleOrderTest {

    @Test
    @AcceptanceCriteria("AC1")
    void orderIsReserved(TestAgent agent) throws Exception {
        ActResult order = agent.act("Place an order for 2 units of SKU-1001 for customer C-42",
                why("A customer checking out is the start of the journey"), on("orders-api"), returns("orderId", "status", "durationMs"));
        assertThat(order.get("status")).isEqualTo("RESERVED");
    }
}
