package io.javaoperatorsdk.operator.sample;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.config.runtime.DefaultConfigurationService;
import org.junit.Test;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.hamcrest.CoreMatchers.*;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

public class IntegrationTest {

    final static String TEST_NS = "tomcat-test";

    @Test
    public void test() {
        Config config = new ConfigBuilder().withNamespace(null).build();
        KubernetesClient client = new DefaultKubernetesClient(config);
        Operator operator = new Operator(client, DefaultConfigurationService.instance());

        TomcatController tomcatController = new TomcatController(client);
        operator.register(tomcatController);

        operator.register(new WebappController(client));

        Tomcat tomcat = new Tomcat();
        tomcat.setMetadata(new ObjectMetaBuilder()
                .withName("test-tomcat1")
                .withNamespace(TEST_NS)
                .build());
        tomcat.setSpec(new TomcatSpec());
        tomcat.getSpec().setReplicas(3);
        tomcat.getSpec().setVersion(9);

        MixedOperation<Tomcat, KubernetesResourceList<Tomcat>, Resource<Tomcat>> tomcatClient = client.customResources(Tomcat.class);

        Namespace testNs = new NamespaceBuilder().withMetadata(
                new ObjectMetaBuilder().withName(TEST_NS).build()).build();

        // We perform a pre-run cleanup instead of a post-run cleanup. This is to help with debugging test results
        // when running against a persistent cluster. The test namespace would stay after the test run so we can
        // check what's there, but it would be cleaned up during the next test run.
        client.namespaces().delete(testNs);

        await().atMost(5, MINUTES).until(() -> client.namespaces().withName("tomcat-test").get() == null);

        client.namespaces().createOrReplace(testNs);

        tomcatClient.inNamespace("tomcat-test").create(tomcat);

        await().atMost(2, MINUTES).untilAsserted(() -> {
            Tomcat updatedTomcat = tomcatClient.inNamespace("tomcat-test").withName("test-tomcat1").get();
            assertThat(updatedTomcat.getStatus(), is(notNullValue()));
            assertThat(updatedTomcat.getStatus().getReadyReplicas(), equalTo(3));
        });
    }
}
