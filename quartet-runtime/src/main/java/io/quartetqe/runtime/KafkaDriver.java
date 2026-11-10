package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Kafka: publish literal or schema-generated events, and find events by content. */
final class KafkaDriver implements Driver {
    private final JsonNode config;
    private final Path contextRoot;
    private KafkaProducer<String, String> producer;

    KafkaDriver(JsonNode config, Path contextRoot) {
        this.config = config;
        this.contextRoot = contextRoot;
    }

    @Override
    public List<Tool> tools() {
        return List.of(
                Tool.of("kafka_generate", "Generate valid events from the topic's schema and publish them. "
                        + "Set business fields with overrides using dot paths.", """
                    {"type":"object","required":["topic"],"properties":{
                      "topic":{"type":"string"},"overrides":{"type":"object"},"keyField":{"type":"string"},
                      "count":{"type":"integer"},"seed":{"type":"integer"}}}""", this::generate),
                Tool.of("kafka_produce", "Publish one exact event.", """
                    {"type":"object","required":["topic","value"],"properties":{
                      "topic":{"type":"string"},"key":{"type":"string"},"value":{"type":"object"}}}""", this::produce),
                Tool.of("kafka_consume", "Find the first event whose fields equal 'match' (dot paths). "
                        + "Reports found=false after the timeout and validates the event against the topic schema.", """
                    {"type":"object","required":["topic","match"],"properties":{
                      "topic":{"type":"string"},"match":{"type":"object"},"timeoutMs":{"type":"integer"}}}""", this::consume));
    }

    private Properties baseProperties() {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", config.path("brokers").asText());
        config.path("properties").fields().forEachRemaining(f -> properties.put(f.getKey(), f.getValue().asText()));
        return properties;
    }

    private synchronized KafkaProducer<String, String> producer() {
        if (producer == null) {
            Properties properties = baseProperties();
            properties.put("acks", "all");
            producer = new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer());
        }
        return producer;
    }

    private JsonNode schema(String topic) throws IOException {
        JsonNode link = config.path("schemas").path(topic);
        if (link.isMissingNode()) return null;
        return Json.parse(Files.readString(contextRoot.resolve(link.asText())));
    }

    private JsonNode generate(JsonNode args) throws Exception {
        String topic = args.path("topic").asText();
        JsonNode schema = schema(topic);
        if (schema == null) throw new IllegalArgumentException("No schema is configured for topic " + topic);
        int count = args.path("count").asInt(1);
        long seed = args.path("seed").asLong(42);
        var events = Json.arr();
        for (int i = 0; i < count; i++) {
            JsonNode event = KafkaGenerator.generate(schema, args.path("overrides"), seed + i);
            String key = args.hasNonNull("keyField") ? Json.at(event, args.path("keyField").asText()).asText() : null;
            producer().send(new ProducerRecord<>(topic, key, event.toString())).get();
            events.add(event);
        }
        ObjectNode result = Json.obj();
        result.put("published", count);
        result.set("events", events);
        return Driver.check(result, "published:" + count);
    }

    private JsonNode produce(JsonNode args) throws Exception {
        producer().send(new ProducerRecord<>(args.path("topic").asText(), args.path("key").asText(null), args.path("value").toString())).get();
        return Driver.check(Json.obj().put("published", 1), "published:1");
    }

    private JsonNode consume(JsonNode args) throws Exception {
        String topic = args.path("topic").asText();
        long deadline = System.currentTimeMillis() + args.path("timeoutMs").asLong(10_000);
        Properties properties = baseProperties();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    JsonNode value = Json.parse(record.value());
                    if (matches(value, args.path("match"))) return found(topic, record, value);
                }
            }
        }
        return Driver.check(Json.obj().put("found", false), "found:false");
    }

    private static boolean matches(JsonNode value, JsonNode match) {
        var fields = match.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!Json.at(value, field.getKey()).asText().equals(field.getValue().asText())) return false;
        }
        return true;
    }

    private JsonNode found(String topic, ConsumerRecord<String, String> record, JsonNode value) throws IOException {
        ObjectNode result = Json.obj();
        result.put("found", true);
        result.put("key", record.key());
        result.put("partition", record.partition());
        result.set("value", value);
        JsonNode schema = schema(topic);
        if (schema != null) {
            JsonSchema validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
            Set<ValidationMessage> errors = validator.validate(value);
            result.put("schemaValid", errors.isEmpty());
            var list = result.putArray("schemaErrors");
            errors.forEach(error -> list.add(error.getMessage()));
        }
        return Driver.check(result, "found:true");
    }

    @Override
    public void close() {
        if (producer != null) producer.close(Duration.ofSeconds(5));
    }
}
