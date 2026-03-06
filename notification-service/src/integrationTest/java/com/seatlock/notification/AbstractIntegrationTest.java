package com.seatlock.notification;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final GenericContainer<?> ELASTICMQ =
            new GenericContainer<>("softwaremill/elasticmq-native:latest")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("elasticmq.conf"),
                            "/opt/elasticmq.conf")
                    .withExposedPorts(9324);

    protected static final GenericContainer<?> MAILPIT =
            new GenericContainer<>("axllent/mailpit:latest")
                    .withExposedPorts(1025, 8025);

    static {
        ELASTICMQ.start();
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.sqs.endpoint",
                () -> "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324));
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }
}
