package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/** MongoDB queries. Inserts are available only when the target sets allowWrite. */
final class MongoDriver implements Driver {
    private static final JsonWriterSettings RELAXED = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
    private final JsonNode config;
    private MongoClient client;

    MongoDriver(JsonNode config) {
        this.config = config;
    }

    @Override
    public List<Tool> tools() {
        List<Tool> tools = new ArrayList<>(List.of(
                Tool.of("mongo_find", "Find documents.", """
                    {"type":"object","required":["collection"],"properties":{
                      "collection":{"type":"string"},"filter":{"type":"object"},"limit":{"type":"integer"}}}""", this::find),
                Tool.of("mongo_aggregate", "Run an aggregation pipeline.", """
                    {"type":"object","required":["collection","pipeline"],"properties":{
                      "collection":{"type":"string"},"pipeline":{"type":"array"}}}""", this::aggregate)));
        if (config.path("allowWrite").asBoolean(false)) {
            tools.add(Tool.of("mongo_insert", "Insert one document.", """
                    {"type":"object","required":["collection","document"],"properties":{
                      "collection":{"type":"string"},"document":{"type":"object"}}}""", this::insert));
        }
        return tools;
    }

    private synchronized MongoDatabase database() {
        if (client == null) client = MongoClients.create(config.path("url").asText());
        return client.getDatabase(config.path("database").asText());
    }

    private static final java.util.Set<String> ALWAYS_REFUSED = java.util.Set.of("$function", "$accumulator", "$where");
    private static final java.util.Set<String> WRITES = java.util.Set.of("$out", "$merge");

    /** Server-side code and, on a read-only target, stages that write, wherever they are nested. */
    static void requireSafe(JsonNode node, boolean writable) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (ALWAYS_REFUSED.contains(field.getKey())) {
                    throw new IllegalArgumentException(field.getKey() + " runs code on the database server and is not allowed.");
                }
                if (!writable && WRITES.contains(field.getKey())) {
                    throw new IllegalArgumentException("This database target is read-only, so " + field.getKey() + " is not allowed. "
                            + "To allow changes the target needs allowWrite: true, and it should be a test database.");
                }
                requireSafe(field.getValue(), writable);
            });
        } else if (node.isArray()) {
            node.forEach(item -> requireSafe(item, writable));
        }
    }

    private JsonNode find(JsonNode args) {
        requireSafe(args.path("filter"), config.path("allowWrite").asBoolean(false));
        Document filter = args.has("filter") ? Document.parse(args.get("filter").toString()) : new Document();
        var documents = new ArrayList<Document>();
        database().getCollection(args.path("collection").asText()).find(filter).limit(args.path("limit").asInt(50)).into(documents);
        return rows(documents);
    }

    private JsonNode aggregate(JsonNode args) {
        requireSafe(args.path("pipeline"), config.path("allowWrite").asBoolean(false));
        List<Document> pipeline = new ArrayList<>();
        args.path("pipeline").forEach(stage -> pipeline.add(Document.parse(stage.toString())));
        var documents = new ArrayList<Document>();
        database().getCollection(args.path("collection").asText()).aggregate(pipeline).into(documents);
        return rows(documents);
    }

    private JsonNode insert(JsonNode args) {
        database().getCollection(args.path("collection").asText()).insertOne(Document.parse(args.path("document").toString()));
        return Driver.check(Json.obj().put("inserted", 1), "inserted:1");
    }

    private static JsonNode rows(List<Document> documents) {
        ObjectNode result = Json.obj();
        ArrayNode rows = result.putArray("rows");
        documents.forEach(document -> rows.add(Json.parse(document.toJson(RELAXED))));
        result.put("rowCount", rows.size());
        return Driver.check(result, rows.isEmpty() ? "rows:empty" : "rows:some");
    }

    @Override
    public void close() {
        if (client != null) client.close();
    }
}
