package org.testcontainers.providers.kubernetes.repository;

import org.testcontainers.controller.intents.BuildImageIntent;
import org.testcontainers.providers.kubernetes.intents.BuildImageK8sIntent;

public class NoRepositoryStrategy implements RepositoryStrategy {
    @Override
    public String getRandomImageName() {
        throw new RuntimeException("No image strategy configured."); // TODO: Custom exception? Helpful description!
    }

    @Override
    public BuildImageIntent setupBuildIntent(BuildImageK8sIntent buildImageIntent) {
        throw new RuntimeException("No image strategy configured."); // TODO: Custom exception? Helpful description!
    }
}
