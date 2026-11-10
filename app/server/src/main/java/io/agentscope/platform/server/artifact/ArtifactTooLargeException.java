package io.agentscope.platform.server.artifact;

/** 产物超过内联上限，应转直传路径（#10）。 */
public class ArtifactTooLargeException extends RuntimeException {

    public ArtifactTooLargeException(String message) {
        super(message);
    }
}
