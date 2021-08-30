package io.javaoperatorsdk.operator.sample;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.*;

@Controller
public class WebappController implements ResourceController<Webapp> {

  private KubernetesClient kubernetesClient;

  private final Logger log = LoggerFactory.getLogger(getClass());

  public WebappController(KubernetesClient kubernetesClient) {
    this.kubernetesClient = kubernetesClient;
  }

  @Override
  public UpdateControl<Webapp> createOrUpdateResource(Webapp webapp, Context<Webapp> context) {
    log.info("UpdateControl");
    if (webapp.getStatus() != null && Objects.equals(webapp.getSpec().getUrl(), webapp.getStatus().getDeployedArtifact())) {
      return UpdateControl.noUpdate();
    }
    if (webapp.getStatus() == null) {
      webapp.setStatus(new WebappStatus());
    }
    log.info(MessageFormat.format("looking for configMap {0}", webapp.getSpec().getTomcat()));
    ConfigMap configMap = kubernetesClient
            .configMaps()
            .inNamespace(webapp.getMetadata().getNamespace())
            .withName(webapp.getSpec().getTomcat()).get();

    if (configMap ==null){
      webapp.getStatus().setReady("False");
      webapp.getStatus().setMessage(MessageFormat.format("configMap {0} does not exist", webapp.getSpec().getTomcat()));
      webapp.getStatus().setUpdateTimestamp(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));

      log.info(MessageFormat.format("configMap {0} is not ready yet", webapp.getSpec().getTomcat()));
      return UpdateControl.noUpdate();//.updateStatusSubResource(webapp);
    }

    String warList = configMap.getData().get("war-list.txt");
    String newWebapp = MessageFormat.format("{0}={1}", webapp.getSpec().getContextPath(), webapp.getSpec().getUrl());
    String newWarList = "";

    if(StringUtils.isNotBlank(webapp.getStatus().getDeployedArtifact()) &&
            StringUtils.isNotBlank(webapp.getStatus().getDeployedContextPath()) ) {

      String previousWebapp = MessageFormat.format("{0}={1}", webapp.getStatus().getDeployedContextPath(), webapp.getStatus().getDeployedArtifact());

      log.info(MessageFormat.format("replace {0} with {1}", previousWebapp, newWebapp));
      newWarList = warList.replace(previousWebapp, newWebapp);
    }else{
      StringBuilder sb = new StringBuilder();
      newWarList = sb.append(newWebapp).append("\n").append(warList).toString();
    }
    configMap.getData().put("war-list.txt", newWarList);
    kubernetesClient.configMaps()
            .inNamespace(webapp.getMetadata().getNamespace())
            .createOrReplace(configMap);

    Deployment existingDeployment =
            kubernetesClient
                    .apps()
                    .deployments()
                    .inNamespace(webapp.getMetadata().getNamespace())
                    .withName(webapp.getSpec().getTomcat())
                    .get();
    if (existingDeployment == null) {
      webapp.getStatus().setReady("False");
      webapp.getStatus().setMessage(MessageFormat.format("Deployment {0} does not exist", webapp.getSpec().getTomcat()));
      webapp.getStatus().setUpdateTimestamp(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));
      log.info(MessageFormat.format("Deployment {0} Deployment", webapp.getSpec().getTomcat()));
      return UpdateControl.updateStatusSubResource(webapp);
    }
    if (existingDeployment.getSpec()
            .getTemplate()
            .getMetadata()
            .getAnnotations() !=null) {
      existingDeployment.getSpec()
              .getTemplate()
              .getMetadata()
              .getAnnotations()
              .put("tomcat-operator-updated", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));
    }else{
      Map<String,String> annotations = new HashMap<>();
      annotations.put("tomcat-operator-updated", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));
      existingDeployment.getSpec()
              .getTemplate()
              .getMetadata()
              .setAnnotations(annotations);
    }

    webapp.getStatus().setDeployedArtifact(webapp.getSpec().getUrl());
    webapp.getStatus().setDeployedContextPath(webapp.getSpec().getContextPath());
    webapp.getStatus().setReady("True");
    webapp.getStatus().setMessage(MessageFormat.format("Deployment {0} Updated", webapp.getSpec().getTomcat()));
    webapp.getStatus().setUpdateTimestamp(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));
    kubernetesClient.apps().deployments().inNamespace(webapp.getMetadata().getNamespace()).createOrReplace(existingDeployment);
    return UpdateControl.updateStatusSubResource(webapp);
  }

  @Override
  public DeleteControl deleteResource(Webapp webapp, Context<Webapp> context) {

    String[] command = new String[] {"rm", "/data/" + webapp.getSpec().getContextPath() + ".war"};
    executeCommandInAllPods(kubernetesClient, webapp, command);
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
