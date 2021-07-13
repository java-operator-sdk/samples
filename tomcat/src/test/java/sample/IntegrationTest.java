package sample;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.config.runtime.DefaultConfigurationService;
import io.javaoperatorsdk.operator.sample.TomcatController;
import io.javaoperatorsdk.operator.sample.WebappController;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.*;

public class IntegrationTest {
    @Test
    public void test() {
        Config config = new ConfigBuilder().withNamespace(null).build();
        KubernetesClient client = new DefaultKubernetesClient(config);
        Operator operator = new Operator(client, DefaultConfigurationService.instance());

        TomcatController tomcatController = new TomcatController(client);
        operator.register(tomcatController);

        operator.register(new WebappController(client));

    }

    private <T> T loadYaml(Class<T> clazz, String yaml) {
        try (InputStream is = getClass().getResourceAsStream(yaml)) {
            return Serialization.unmarshal(is, clazz);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot find yaml on classpath: " + yaml);
        }
    }
}
