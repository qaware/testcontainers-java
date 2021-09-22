package org.testcontainers.providers.kubernetes.repository;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.api.model.extensions.IngressBuilder;
import io.fabric8.kubernetes.api.model.extensions.IngressTLSBuilder;
import lombok.SneakyThrows;
import org.testcontainers.controller.intents.BuildImageIntent;
import org.testcontainers.providers.kubernetes.KubernetesContext;
import org.testcontainers.providers.kubernetes.RecurringException;
import org.testcontainers.providers.kubernetes.intents.BuildImageK8sIntent;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class TemporaryImageRepositoryStrategy implements RepositoryStrategy {

    public static final String REGISTRY_PORT_NAME = "registry";
    private final String registryImage = "registry:2.7.1";
    private final KubernetesContext context;

    private final String ingressHostName;
    private final String ingressTlsSecretName;
    private final Map<String, String> ingressAnnotations;
    private final boolean skipCertificateCheck;
    private final int containerPort = 5000;

    private RegistryStartupException startupException;


    private Ingress registryIngress = null;

    public TemporaryImageRepositoryStrategy(
        KubernetesContext context,
        String ingressHostName,
        String ingressTlsSecretName,
        Map<String, String> ingressAnnotations,
        boolean skipCertificateCheck
    ) {
        this.context = context;
        this.ingressHostName = ingressHostName;
        this.ingressTlsSecretName = ingressTlsSecretName;
        this.ingressAnnotations = ingressAnnotations;
        this.skipCertificateCheck = skipCertificateCheck;
    }


    @Override
    public String getRandomImageName() {
        return String.format("%s/testcontainers/%s", getBaseImagePrefix(), UUID.randomUUID().toString().toLowerCase());
    }

    @Override
    public BuildImageIntent setupBuildIntent(BuildImageK8sIntent buildImageIntent) {
        if (skipCertificateCheck) {
            buildImageIntent.getExtendedBuildParams().setSkipTlsVerify(true);
        }
        return buildImageIntent;
    }

    private String getBaseImagePrefix() {
        Ingress regIngress = getRegistryIngress();
        return regIngress.getSpec().getRules().get(0).getHost();
    }

    @SneakyThrows
    private synchronized Ingress getRegistryIngress() {
        if (registryIngress == null) {
            registryIngress = trySetup();
        }
        return registryIngress;
    }

    private Ingress trySetup() throws RecurringException, RegistryStartupException {
        if (startupException != null) {
            throw new RecurringException(startupException);
        }
        try {
            return setup();
        } catch (RegistryStartupException e) {
            startupException = e;
            throw e;
        }
    }

    private Ingress setup() throws RegistryStartupException {
        Map<String, String> registryLabels = new HashMap<>();
        registryLabels.put("testcontainers-component", "registry");
        // @formatter:off
        Deployment deployment = new DeploymentBuilder()
            .editOrNewMetadata()
                .withName("repository")
                .withNamespace(context.getNamespaceProvider().getNamespace())
                .withLabels(registryLabels)
            .endMetadata()
            .editOrNewSpec()
                .editOrNewSelector()
                    .withMatchLabels(registryLabels)
                .endSelector()
                .editOrNewTemplate()
                    .editOrNewMetadata()
                        .withLabels(registryLabels)
                    .endMetadata()
                    .editOrNewSpec()
                        .addNewContainer()
                            .withName("registry")
                            .withImage(registryImage)
                            .addNewPort()
                                .withContainerPort(containerPort)
                                .withProtocol("TCP")
                                .withName(REGISTRY_PORT_NAME)
                            .endPort()
                        .endContainer()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();
        // @formatter:on


        Deployment createdDeployment = context.getClient()
            .apps()
            .deployments()
            .create(deployment);

        context.getResourceReaper().registerDeploymentForCleanup(createdDeployment);

        // @formatter:off
        Service service = new ServiceBuilder()
            .editOrNewMetadata()
                .withName("registry")
                .withNamespace(context.getNamespaceProvider().getNamespace())
                .withLabels(registryLabels)
            .endMetadata()
            .editOrNewSpec()
                .withType("ClusterIP")
                .withSelector(registryLabels)
                .addNewPort()
                    .withName(REGISTRY_PORT_NAME)
                    .withTargetPort(new IntOrString(REGISTRY_PORT_NAME))
                    .withProtocol("TCP")
                    .withPort(containerPort)
                .endPort()
            .endSpec()
            .build();
        // @formatter:on

        Service createdService = context.getClient()
            .services()
            .create(service);

        context.getResourceReaper().registerServiceForCleanup(createdService);

        // @formatter:on
        Ingress ingress = new IngressBuilder()
            .editOrNewMetadata()
            .withName("registry")
            .withNamespace(context.getNamespaceProvider().getNamespace())
            .withLabels(registryLabels)
            .withAnnotations(ingressAnnotations)
            .endMetadata()
            .editOrNewSpec()
            .addNewRule()
            .withHost(ingressHostName)
            .editOrNewHttp()
            .addNewPath()
            .editOrNewBackend()
            .withServiceName(createdService.getMetadata().getName())
            .withServicePort(new IntOrString(REGISTRY_PORT_NAME))
            .endBackend()
            .endPath()
            .endHttp()
            .endRule()
            .addToTls(
                new IngressTLSBuilder()
                    .withHosts(ingressHostName)
                    .withSecretName(ingressTlsSecretName)
                    .build()
            )
            .endSpec()
            .build();
        // @formatter:off

        Ingress createdIngress = context.getClient()
            .extensions()
            .ingresses()
            .create(ingress);

        context.getResourceReaper().registerIngressForCleanup(createdIngress);

        context.getClient()
            .apps()
            .deployments()
            .inNamespace(createdDeployment.getMetadata().getNamespace())
            .withName(createdDeployment.getMetadata().getName())
            .waitUntilReady(3, TimeUnit.MINUTES);

        if(skipCertificateCheck) {
            return ingress;
        }

        try {
            Instant started = Instant.now();
            while(true) {
                if(probeTLS(ingressHostName)) {
                   return createdIngress;
                }
                Instant now = Instant.now();
                if(ChronoUnit.MINUTES.between(started, now) > 3) {
                    throw new InterruptedException();
                }
                Thread.sleep(TimeUnit.SECONDS.toMillis(10));
            }
        }catch (InterruptedException ignored) { }

        throw new RegistryStartupException("Timed out waiting for TLS to be available");
    }

    private boolean probeTLS(String host) {
        try {
            SSLSocketFactory sslsocketfactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslsocket = (SSLSocket) sslsocketfactory.createSocket(host, 443);

            SSLParameters sslparams = new SSLParameters();
            sslparams.setEndpointIdentificationAlgorithm("HTTPS");
            sslsocket.setSSLParameters(sslparams);

            try(InputStream in = sslsocket.getInputStream()) {
                try(OutputStream out = sslsocket.getOutputStream()) {
                    out.write(1);

                    while (in.available() > 0) {
                        System.out.print(in.read());
                    }
                    return true;
                }
            }
        }catch (Exception e) {
            return false;
        }
    }
}
