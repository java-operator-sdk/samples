package io.javaoperatorsdk.operator.sample;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.*;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Objects;

@Controller
public class WebappController implements ResourceController<Webapp> {

  private KubernetesClient kubernetesClient;

  private final Logger log = LoggerFactory.getLogger(getClass());

  public WebappController(KubernetesClient kubernetesClient) {
    this.kubernetesClient = kubernetesClient;
  }

  @Override
  public void init(EventSourceManager eventSourceManager) {
    TomcatEventSource tomcatEventSource = TomcatEventSource.createAndRegisterWatch(kubernetesClient);
    eventSourceManager.registerEventSource("tomcat-event-source", tomcatEventSource);
  }

  /**
   * This method will be called not only on changes to Webapp objects but also when Tomcat objects change.
   */
  @Override
  public UpdateControl<Webapp> createOrUpdateResource(Webapp webapp, Context<Webapp> context) {
    if (webapp.getStatus() != null && Objects.equals(webapp.getSpec().getUrl(), webapp.getStatus().getDeployedArtifact())) {
      return UpdateControl.noUpdate();
    }

    var tomcatClient = kubernetesClient.customResources(Tomcat.class);
    Tomcat tomcat = tomcatClient.inNamespace(webapp.getMetadata().getNamespace()).withName(webapp.getSpec().getTomcat()).get();
    if (tomcat == null) {
      throw new IllegalStateException("Cannot find Tomcat " + webapp.getSpec().getTomcat() + " for Webapp " + webapp.getMetadata().getName() + " in namespace " + webapp.getMetadata().getNamespace());
    }

    if (tomcat.getStatus() != null && Objects.equals(tomcat.getSpec().getReplicas(), tomcat.getStatus().getReadyReplicas())) {
      log.info("Tomcat is ready and webapps not yet deployed. Commencing deployment of {} in Tomcat {}", webapp.getMetadata().getName(), tomcat.getMetadata().getName());
      String[] command = new String[]{"wget", "-O", "/data/" + webapp.getSpec().getContextPath() + ".war", webapp.getSpec().getUrl()};

      executeCommandInAllPods(kubernetesClient, webapp, command);

      if (webapp.getStatus() == null) {
        webapp.setStatus(new WebappStatus());
      }
      webapp.getStatus().setDeployedArtifact(webapp.getSpec().getUrl());
      return UpdateControl.updateStatusSubResource(webapp);
    } else {
      log.info("WebappController invoked but Tomcat not ready yet ({}/{})", tomcat.getSpec().getReplicas(),
              tomcat.getStatus() != null ? tomcat.getStatus().getReadyReplicas() : 0);
      return UpdateControl.noUpdate();
    }
  }

  @Override
  public DeleteControl deleteResource(Webapp webapp, Context<Webapp> context) {

    String[] command = new String[] {"rm", "/data/" + webapp.getSpec().getContextPath() + ".war"};
    executeCommandInAllPods(kubernetesClient, webapp, command);
    if (webapp.getStatus() != null) {
      webapp.getStatus().setDeployedArtifact(null);
    }
    return DeleteControl.DEFAULT_DELETE;
  }

  private void executeCommandInAllPods(
      KubernetesClient kubernetesClient, Webapp webapp, String[] command) {
    Deployment deployment =
        kubernetesClient
            .apps()
            .deployments()
            .inNamespace(webapp.getMetadata().getNamespace())
            .withName(webapp.getSpec().getTomcat())
            .get();

    if (deployment != null) {
      List<Pod> pods =
          kubernetesClient
              .pods()
              .inNamespace(webapp.getMetadata().getNamespace())
              .withLabels(deployment.getSpec().getSelector().getMatchLabels())
              .list()
              .getItems();
      for (Pod pod : pods) {
        log.info(
            "Executing command {} in Pod {}",
            String.join(" ", command),
            pod.getMetadata().getName());
        kubernetesClient
            .pods()
            .inNamespace(deployment.getMetadata().getNamespace())
            .withName(pod.getMetadata().getName())
            .inContainer("war-downloader")
            .writingOutput(new ByteArrayOutputStream())
            .writingError(new ByteArrayOutputStream())
            .exec(command);
      }
    }
  }
}
