package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Tool;
import java.util.List;

/** Connects one target type to the tools the agent can use on it. */
public interface Driver extends AutoCloseable {
    List<Tool> tools();

    @Override
    default void close() throws Exception {}

    /** Records the outcome a replay must reproduce, for example "status:201". */
    static ObjectNode check(ObjectNode result, String replayCheck) {
        return result.put("replayCheck", replayCheck);
    }
}
