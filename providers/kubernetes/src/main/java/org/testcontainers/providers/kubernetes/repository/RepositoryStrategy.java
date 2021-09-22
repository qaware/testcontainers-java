package org.testcontainers.providers.kubernetes.repository;

import org.testcontainers.controller.intents.BuildImageIntent;
import org.testcontainers.providers.kubernetes.intents.BuildImageK8sIntent;

public interface RepositoryStrategy {
    String getRandomImageName();

    BuildImageIntent setupBuildIntent(BuildImageK8sIntent buildImageIntent);
}
