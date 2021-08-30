package io.javaoperatorsdk.operator.sample;

public class WebappStatus {

  private String deployedArtifact;

  public String getDeployedArtifact() {
    return deployedArtifact;
  }

  public void setDeployedArtifact(String deployedArtifact) {
    this.deployedArtifact = deployedArtifact;
  }

  private String ready;

  public String getReady() { return ready; }

  public void setReady(String ready) { this.ready = ready; }

  private String message;

  public String getMessage() { return message; }

  public void setMessage(String message) { this.message = message; }

  private String updateTimestamp;

  public String getUpdateTimestamp() { return updateTimestamp; }

  public void setUpdateTimestamp(String updateTimestamp) { this.updateTimestamp = updateTimestamp; }

  private String contextPath;

  public void setDeployedContextPath(String contextPath) { this.contextPath = contextPath; }

  public String getDeployedContextPath(){ return contextPath; }
}
