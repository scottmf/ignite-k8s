/* ***************************************************************************
 * Copyright 2017 VMware, Inc.  All rights reserved.
 * -- VMware Confidential
 * **************************************************************************/
package com.scottieknows.ignite;

import static java.lang.String.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinderAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;

public class IgnitePodResolver extends TcpDiscoveryIpFinderAdapter {
    private static final Logger logger = LoggerFactory.getLogger(IgnitePodResolver.class);

    private TimeUtils timeUtils = new TimeUtils();

    private static final String K8S_NAMESPACE_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";
    private static final String K8S_TOKEN_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private static final String K8S_CA_CRT_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";

    private final Map<String, Set<String>> appLabelToPodMap = new HashMap<>();
    private DefaultKubernetesClient client;
    private String oauthToken;
    private String namespace;
    @Value("${ignite.kube.master:https://kubernetes.default.svc.cluster.local:443}")
    private String kubeMaster;
    @Value("${ignite.kube.appLabel:ignite}")
    private String appLabel;

    @PostConstruct
    public void init() {
        setDefaults();
        Config config = getConfig();
        this.client = new DefaultKubernetesClient(config);
        // ensure that the watcher does not override the map until init is complete
        synchronized (appLabelToPodMap) {
            appLabelToPodMap.clear();
            client.pods().watch(new KubePodWatcher(this));
            initializePodMap();
            logger.info("initialized pod map {}", appLabelToPodMap);
        }
    }

    private void setDefaults() {
        if (namespace == null) {
            setNamespaceFromFile(K8S_NAMESPACE_FILE);
        }
        if (oauthToken == null) {
            setOauthTokenFromFile(K8S_TOKEN_FILE);
        }
    }

    void initializePodMap() {
        NonNamespaceOperation<Pod, PodList, DoneablePod, PodResource<Pod, DoneablePod>> op =
            client.pods().inNamespace(namespace);
        PodList list = op.list();
        list.getItems().forEach(p -> {
            String appLabel = getAppLabel(p.getMetadata());
            if (appLabel == null) {
                return;
            }
            String name = p.getStatus().getPodIP();
            addPodToMap(appLabel, name);
        });
    }

    void addPodToMap(String appLabel, String name) {
        synchronized (appLabelToPodMap) {
            Set<String> podSet = appLabelToPodMap.get(appLabel);
            if (podSet == null) {
                podSet = new HashSet<>();
                appLabelToPodMap.put(appLabel, podSet);
            }
            podSet.add(name);
        }
    }

    public class KubePodWatcher implements Watcher<Pod> {

        private IgnitePodResolver kubeUriResolver;

        public KubePodWatcher(IgnitePodResolver kubeUriResolver) {
            this.kubeUriResolver = kubeUriResolver;
        }

        @Override
        public void eventReceived(Action action, Pod pod) {
            ObjectMeta metadata = pod.getMetadata();
            PodStatus status = pod.getStatus();
            String ns = metadata.getNamespace();
            if (!Objects.equals(namespace, ns)) {
                return;
            }
            String kind = pod.getKind();
            logger.debug("{} {}", action, pod);
            if (!Objects.equals(kind, "Pod")) {
                return;
            }
            String appLabel = getAppLabel(metadata);
            if (appLabel == null) {
                return;
            }
            if (Action.DELETED == action) {
                synchronized (appLabelToPodMap) {
                    appLabelToPodMap.remove(metadata.getName());
                }
                logger.debug("pod appLabel={} name={} namespace={} removed from map",
                    appLabel, metadata.getName(), ns);
            } else {
                addPodToMap(appLabel, status.getPodIP());
                logger.debug("pod appLabel={} name={} namespace={} added to map",
                    appLabel, metadata.getName(), ns);
            }
        }

        @Override
        public void onClose(KubernetesClientException cause) {
            logger.error("k8s master connection lost: {}", cause.getMessage(), cause);
            boolean first = true;
            long increment = 1000;
            long backoff = 0;
            while (true) {
                try {
                    if (!first) {
                        timeUtils.sleep(Math.min(backoff += increment, 10000) );
                    }
                    first = false;
                    logger.info("reinitializing kubernetes client");
                    kubeUriResolver.init();
                    break;
                } catch (Exception e) {
                    logger.error("problem reinitializing kubnernetes client, retrying: {}", e.getMessage(), e);
                }
            }
        }

    }

    private Config getConfig() {
        logger.info("building k8s client with master={}, token is not null or empty {}",
            kubeMaster, !StringUtils.isEmpty(oauthToken));
        return new ConfigBuilder()
            .withCaCertFile(K8S_CA_CRT_FILE)
            .withMasterUrl(kubeMaster)
            .withOauthToken(oauthToken)
            .build();
    }

    // package private for tests
    String getFileContents(String file) {
        try {
            return new String(Files.readAllBytes(Paths.get(file)));
        } catch (IOException e) {
            throw new RuntimeException(format("Failed to read contents from file %s", file), e);
        }
    }

    private String getAppLabel(ObjectMeta metadata) {
        Map<String, String> labels = metadata.getLabels();
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        return labels.get("app");
    }

    // package private for tests
    void setClient(DefaultKubernetesClient client) {
        this.client = client;
    }

    // package private for tests
    void setTimeUtils(TimeUtils timeUtils) {
        this.timeUtils = timeUtils;
    }

    @Override
    public Collection<InetSocketAddress> getRegisteredAddresses() throws IgniteSpiException {
        Collection<InetSocketAddress> rtn = new ArrayList<>();
        Collection<String> names = new ArrayList<>();
        synchronized (appLabelToPodMap) {
            names.addAll(appLabelToPodMap.get(appLabel));
        }
        names.forEach(name -> rtn.add(new InetSocketAddress(name, TcpDiscoverySpi.DFLT_PORT)));
        return rtn;
    }

    @Override
    public void registerAddresses(Collection<InetSocketAddress> addrs) throws IgniteSpiException {}

    @Override
    public void unregisterAddresses(Collection<InetSocketAddress> addrs) throws IgniteSpiException {}

    public void setNamespaceFromFile(String namespaceFile) {
        this.namespace = getFileContents(namespaceFile);
    }

    public void setOauthTokenFromFile(String tokenFile) {
        this.oauthToken = getFileContents(tokenFile);
    }

    public void setKubeMaster(String kubeMaster) {
        this.kubeMaster = kubeMaster;
    }

    public String getKubeMaster() {
        return kubeMaster;
    }

    public String getAppLabel() {
        return appLabel;
    }

    public void setAppLabel(String appLabel) {
        this.appLabel = appLabel;
    }

    void setOauthToken(String oauthToken) {
        this.oauthToken = oauthToken;
    }

    void setNamespace(String namespace) {
        this.namespace = namespace;
    }

}
