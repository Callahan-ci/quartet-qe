package demo.shopflow;

import com.mongodb.client.MongoClients;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.process.io.ProcessOutput;
import de.flapdoodle.reverse.transitions.Start;
import de.flapdoodle.embed.mongo.config.Net;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/** Starts real Kafka, PostgreSQL and MongoDB in-process, with no Docker, and then the demo service. */
public final class ShopflowMain {
    static final int HTTP_PORT = 18080;
    static final int POSTGRES_PORT = 15432;
    static final int MONGO_PORT = 27117;
    static final int KAFKA_PORT = 19092;

    private ShopflowMain() {}

    /** The embedded broker picks its own port. This gives clients a stable address to bootstrap from. */
    private static void forwardKafkaPort(int port, String brokers) throws IOException {
        String[] target = brokers.split(",")[0].split(":");
        ServerSocket listener = new ServerSocket(port);
        Thread acceptor = new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    Socket broker = new Socket(target[0], Integer.parseInt(target[1]));
                    pipe(client, broker);
                    pipe(broker, client);
                } catch (IOException e) {
                    System.err.println("Kafka forward problem: " + e.getMessage());
                }
            }
        }, "kafka-forward");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private static void pipe(Socket from, Socket to) {
        Thread thread = new Thread(() -> {
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                in.transferTo(out);
            } catch (IOException connectionClosed) {
                // Either side closing the connection ends this direction.
            }
        }, "kafka-pipe");
        thread.setDaemon(true);
        thread.start();
    }

    public static void main(String[] args) throws Exception {
        EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(POSTGRES_PORT).start();
        System.out.println("PostgreSQL started");

        var mongod = Mongod.instance()
                .withNet(Start.to(Net.class).initializedWith(Net.defaults().withPort(MONGO_PORT)))
                .withProcessOutput(Start.to(ProcessOutput.class).initializedWith(ProcessOutput.silent()))
                .start(Version.Main.V8_0);
        System.out.println("MongoDB started");

        var kafka = new EmbeddedKafkaKraftBroker(1, 1, "shop.orders.created", "shop.payments.settled");
        kafka.afterPropertiesSet();
        forwardKafkaPort(KAFKA_PORT, kafka.getBrokersAsString());
        System.out.println("Kafka started");

        Connection connection = postgres.getPostgresDatabase().getConnection();
        ShopService service = new ShopService(connection, MongoClients.create("mongodb://localhost:" + MONGO_PORT).getDatabase("catalog"), kafka.getBrokersAsString());
        service.start(HTTP_PORT);
        System.out.println("ShopFlow ready on http://localhost:" + HTTP_PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                service.stop();
                kafka.destroy();
                mongod.close();
                postgres.close();
            } catch (Exception e) {
                System.err.println("Shutdown problem: " + e.getMessage());
            }
        }));
        new CountDownLatch(1).await();
    }
}
