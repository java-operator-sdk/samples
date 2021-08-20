package io.javaoperatorsdk.operator.sample;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.config.runtime.DefaultConfigurationService;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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

        operator.register(new TomcatController(client));
        operator.register(new WebappController(client));

        Tomcat tomcat = new Tomcat();
        tomcat.setMetadata(new ObjectMetaBuilder()
                .withName("test-tomcat1")
                .withNamespace(TEST_NS)
                .build());
        tomcat.setSpec(new TomcatSpec());
        tomcat.getSpec().setReplicas(3);
        tomcat.getSpec().setVersion(9);

        Webapp webapp1 = new Webapp();
        webapp1.setMetadata(new ObjectMetaBuilder()
                .withName("test-webapp1")
                .withNamespace(TEST_NS)
                .build());
        webapp1.setSpec(new WebappSpec());
        webapp1.getSpec().setContextPath("webapp1");
        webapp1.getSpec().setTomcat(tomcat.getMetadata().getName());
        webapp1.getSpec().setUrl("http://tomcat.apache.org/tomcat-7.0-doc/appdev/sample/sample.war");

        var tomcatClient = client.customResources(Tomcat.class);
        var webappClient = client.customResources(Webapp.class);

        Namespace testNs = new NamespaceBuilder().withMetadata(
                new ObjectMetaBuilder().withName(TEST_NS).build()).build();

        // We perform a pre-run cleanup instead of a post-run cleanup. This is to help with debugging test results
        // when running against a persistent cluster. The test namespace would stay after the test run so we can
        // check what's there, but it would be cleaned up during the next test run.
        client.namespaces().delete(testNs);

        await().atMost(5, MINUTES).until(() -> client.namespaces().withName("tomcat-test").get() == null);

        client.namespaces().createOrReplace(testNs);

        tomcatClient.inNamespace(TEST_NS).create(tomcat);
        webappClient.inNamespace(TEST_NS).create(webapp1);

        await().atMost(1, MINUTES).until(() -> client.services().inNamespace(TEST_NS).withName(tomcat.getMetadata().getName()).get() != null);
        LocalPortForward localPortForward = client.services().inNamespace(TEST_NS).withName(tomcat.getMetadata().getName()).portForward(80);

        await().atMost(2, MINUTES).untilAsserted(() -> {
            Tomcat updatedTomcat = tomcatClient.inNamespace(TEST_NS).withName(tomcat.getMetadata().getName()).get();
            Webapp updatedWebapp = webappClient.inNamespace(TEST_NS).withName(webapp1.getMetadata().getName()).get();
            assertThat(updatedTomcat.getStatus(), is(notNullValue()));
            assertThat(updatedTomcat.getStatus().getReadyReplicas(), equalTo(3));
            assertThat(updatedWebapp.getStatus(), is(notNullValue()));
            assertThat(updatedWebapp.getStatus().getDeployedArtifact(), is(notNullValue()));

            URI uri = URI.create("http://localhost:" + localPortForward.getLocalPort() + "/" + webapp1.getSpec().getContextPath());
            try {
                HttpClient httpClient = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .build();
                int statusCode = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
                assertThat("Failed to access " + uri, statusCode, equalTo(200));
            } catch (IOException ex) {
                throw new AssertionError("Failed to access " + uri, ex);
            }
        });
    }
}
