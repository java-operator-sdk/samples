package io.javaoperatorsdk.operator.sample;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.config.runtime.DefaultConfigurationService;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

public class IntegrationTest {
    @Test
    public void test() {
        Config config = new ConfigBuilder().withNamespace(null).build();
        KubernetesClient client = new DefaultKubernetesClient(config);
        Operator operator = new Operator(client, DefaultConfigurationService.instance());

        TomcatController tomcatController = new TomcatController(client);
        operator.register(tomcatController);

        operator.register(new WebappController(client));

        Tomcat tomcat = loadYaml(Tomcat.class, "k8s/tomcat-sample1.yaml");

        tomcat.getSpec().setReplicas(3);
        tomcat.getMetadata().setNamespace("tomcat-test");

        MixedOperation<Tomcat, KubernetesResourceList<Tomcat>, Resource<Tomcat>> tomcatClient = client.customResources(Tomcat.class);

        Namespace testNs = new NamespaceBuilder().withMetadata(
                new ObjectMetaBuilder().withName("tomcat-test").build()).build();

        // We perform a pre-run cleanup instead of a post-run cleanup. This is to help with debugging test results
        // when running against a persistent cluster. The test namespace would stay after the test run so we can
        // check what's there, but it would be cleaned up during the next test run.
        client.namespaces().delete(testNs);

        await().atMost(300, SECONDS).until(() -> client.namespaces().withName("tomcat-test").get() == null);

        client.namespaces().createOrReplace(testNs);

        tomcatClient.inNamespace("tomcat-test").create(tomcat);

        await().atMost(60, SECONDS).until(() -> {
            Tomcat updatedTomcat = tomcatClient.inNamespace("tomcat-test").withName("test-tomcat1").get();
            return updatedTomcat.getStatus() != null && updatedTomcat.getStatus().getReadyReplicas() == 3;
        });
    }

    private <T> T loadYaml(Class<T> clazz, String yaml) {
        try (InputStream is = new FileInputStream(yaml)) {
            return Serialization.unmarshal(is, clazz);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot find yaml on classpath: " + yaml);
        }
    }
}
