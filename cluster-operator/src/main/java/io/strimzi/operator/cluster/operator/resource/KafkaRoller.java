/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.common.BackOff;
import io.strimzi.operator.common.MaxAttemptsExceededException;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.PodOperator;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.Node;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.stream.IntStream.range;

/**
 * <p>Manages the rolling restart of a Kafka cluster.</p>
 *
 * <p>The following algorithm is used:</p>
 *
 * <pre>
 * For each pod:
 *   1. Test whether the pod needs to be restarted.
 *       If not then:
 *         1. Continue to the next pod
 *   2. Otherwise, check whether the pod is the controller
 *       If so, and there are still pods to be maybe-rolled then:
 *         1. Add this pod to the end of the list
 *         2. Continue to the next pod
 *   3. Otherwise, check whether the pod can be restarted without "impacting availability"
 *       If not then:
 *         1. Add this pod to the end of the list
 *         2. Continue to the next pod
 *   4. Otherwise:
 *       1 Restart the pod
 *       2. Wait for it to become ready (in the kube sense)
 *       3. Continue to the next pod
 * </pre>
 *
 * <p>"impacting availability" is defined by {@link KafkaAvailability}.</p>
 *
 * <p>Note this algorithm still works if there is a spontaneous
 * change in controller while the rolling restart is happening.</p>
 */
public class KafkaRoller {

    private static final Logger log = LogManager.getLogger(KafkaRoller.class);
    private static final String NO_UID = "NULL";

    protected final PodOperator podOperations;
    protected final long pollingIntervalMs;
    protected final long operationTimeoutMs;
    protected final Vertx vertx;
    private final String cluster;
    private final Secret clusterCaCertSecret;
    private final Secret coKeySecret;
    private final PriorityQueue<Monitor> queue = new PriorityQueue<>();
    protected AdminClient ac;
    protected String namespace;
    private final AdminClientProvider adminClientProvider;

    public KafkaRoller(Vertx vertx, PodOperator podOperations,
                       long pollingIntervalMs, long operationTimeoutMs, Supplier<BackOff> backOffSupplier,
                       StatefulSet ss, Secret clusterCaCertSecret, Secret coKeySecret) {
        this(vertx, podOperations, pollingIntervalMs, operationTimeoutMs, backOffSupplier,
                ss, clusterCaCertSecret, coKeySecret, new DefaultAdminClientProvider());
    }

    public KafkaRoller(Vertx vertx, PodOperator podOperations,
                       long pollingIntervalMs, long operationTimeoutMs, Supplier<BackOff> backOffSupplier,
                       StatefulSet ss, Secret clusterCaCertSecret, Secret coKeySecret,
                       AdminClientProvider adminClientProvider) {
        this.namespace = ss.getMetadata().getNamespace();
        this.cluster = Labels.cluster(ss);
        initPods(range(0, ss.getSpec().getReplicas()).boxed().collect(Collectors.toList()), backOffSupplier);
        this.clusterCaCertSecret = clusterCaCertSecret;
        this.coKeySecret = coKeySecret;
        this.vertx = vertx;
        this.operationTimeoutMs = operationTimeoutMs;
        this.podOperations = podOperations;
        this.pollingIntervalMs = pollingIntervalMs;
        this.adminClientProvider = adminClientProvider;
    }

    private static String getPodUid(Pod resource) {
        if (resource == null || resource.getMetadata() == null) {
            return NO_UID;
        }
        return resource.getMetadata().getUid();
    }

    /**
     * Returns a Future which completed with the actual pod corresponding to the abstract representation
     * of the given {@code pod}.
     */
    protected Future<Pod> pod(Integer podId) {
        return podOperations.getAsync(namespace, KafkaCluster.kafkaPodName(cluster, podId));
    }

    /**
     * Perform a rolling restart of the pods in the given StatefulSet.
     * Pods will be tested for whether the really need rolling using the given {@code podNeedsRestart}.
     * If a pod does indeed need restarting {@link #postRestartBarrier(Pod)} is called afterwards.
     * The returned Future is completed when the rolling restart is completed.
     */
    public Future<Void> rollingRestart(
                                Predicate<Pod> podNeedsRestart) {
        Function<Void, Future<KafkaRoller>> x = new Function<Void, Future<KafkaRoller>>() {
            @Override
            public Future<KafkaRoller> apply(Void ignored) {
                return KafkaRoller.this.next(podNeedsRestart).compose(podId -> {
                    if (podId == null) {
                        log.debug("No more pods to restart");
                        return Future.succeededFuture();
                    } else {
                        return pod(podId).compose(p -> {
                            log.debug("Rolling pod {} (still to consider: {})", p.getMetadata().getName(), KafkaRoller.this);
                            Future<Void> f = restartWithPostBarrier(p);
                            return f.compose(this);
                        });
                    }
                });
            }
        };
        try {
            return x.apply(null).map((Void) null);
        } catch (Throwable t) {
            return Future.failedFuture(t);
        }
    }

    /**
     * Asynchronously apply the pre-restart barrier, then restart the given pod
     * by deleting it and letting it be recreated by K8s, then apply the post-restart barrier.
     * Return a Future which completes when the after restart callback for the given pod has completed.
     * @param pod The Pod to restart.
     * @return a Future which completes when the after restart callback for the given pod has completed.
     */
    private Future<Void> restartWithPostBarrier(Pod pod) {
        String podName = pod.getMetadata().getName();
        log.debug("Rolling pod {}", podName);
        return restart(pod).compose(i -> {
            String ssName = podName.substring(0, podName.lastIndexOf('-'));
            log.debug("Rolling update of {}/{}: wait for pod {} postcondition", namespace, ssName, podName);
            return postRestartBarrier(pod);
        });
    }

    /**
     * Asynchronously delete the given pod, return a Future which completes when the Pod has been recreated.
     * Note: The pod might not be "ready" when the returned Future completes.
     * @param pod The pod to be restarted
     * @return a Future which completes when the Pod has been recreated
     */
    protected Future<Void> restart(Pod pod) {
        long pollingIntervalMs = 1_000;
        String name = KafkaCluster.kafkaClusterName(cluster);
        String podName = pod.getMetadata().getName();
        Future<Void> deleteFinished = Future.future();
        log.info("Rolling update of {}/{}: Rolling pod {}", namespace, name, podName);

        // Determine generation of deleted pod
        String deleted = getPodUid(pod);

        // Delete the pod
        log.debug("Rolling update of {}/{}: Waiting for pod {} to be deleted", namespace, name, podName);
        Future<Void> podReconcileFuture =
                podOperations.reconcile(namespace, podName, null).compose(ignore -> {
                    Future<Void> del = podOperations.waitFor(namespace, name, pollingIntervalMs, operationTimeoutMs, (ignore1, ignore2) -> {
                        // predicate - changed generation means pod has been updated
                        String newUid = getPodUid(podOperations.get(namespace, podName));
                        boolean done = !deleted.equals(newUid);
                        if (done) {
                            log.debug("Rolling pod {} finished", podName);
                        }
                        return done;
                    });
                    return del;
                });

        podReconcileFuture.setHandler(deleteResult -> {
            if (deleteResult.succeeded()) {
                log.debug("Rolling update of {}/{}: Pod {} was deleted", namespace, name, podName);
            }
            deleteFinished.handle(deleteResult);
        });
        return deleteFinished;
    }

    protected static class Monitor implements Comparable<Monitor> {
        protected final int podId;
        private long nextDeadline;
        private final BackOff backOff;
        private long priority;
        public Monitor(int podId, BackOff backOff) {
            this.podId = podId;
            this.backOff = backOff;
            this.nextDeadline = 0;
            this.priority = 0;
        }
        /** Re-queue without counting this as an attempt */
        private Monitor retry(Logger logger) {
            priority = System.currentTimeMillis();
            return this;
        }

        /** Queue for retry after a delay */
        private Monitor backoffAndRetry(Logger logger) {
            long delayMs = backOff.delayMs();
            logger.debug("Will retry pod {} in {}ms", podId, delayMs);
            priority = nextDeadline = System.currentTimeMillis() + delayMs;
            return this;
        }

        @Override
        public int compareTo(Monitor other) {
            int cmp = Long.compare(this.priority, other.priority);
            if (cmp == 0) {
                cmp = Integer.compare(this.podId, other.podId);
            }
            return cmp;
        }

        @Override
        public String toString() {
            return "Monitor{" +
                    "podId=" + podId +
                    ", nextDeadline=" + nextDeadline +
                    '}';
        }
    }

    protected Future<Integer> sort(Predicate<Pod> podNeedsRestart) {
        return filterPods(podNeedsRestart)
            .compose(pod -> {
                if (pod != null) {
                    Future<Integer> result = Future.future();
                    adminClient(pod)
                        .compose(i -> findNextRollable(podNeedsRestart))
                        .setHandler(ar -> close(result, ar));
                    return result;
                } else {
                    return Future.succeededFuture();
                }
            });
    }

    /**
     * Returns a Future which completes with an AdminClient instance.
     */
    protected Future<Void> adminClient(Integer podId) {
        String hostname = KafkaCluster.podDnsName(this.namespace, this.cluster, podName(podId)) + ":" + KafkaCluster.REPLICATION_PORT;
        Future<Void> result = Future.future();
        vertx.executeBlocking(
            f -> {
                try {
                    log.debug("Creating AC for {}", hostname);
                    this.ac = adminClientProvider.createAdminClient(hostname, this.clusterCaCertSecret, this.coKeySecret);
                    f.complete();
                } catch (Exception e) {
                    f.fail(e);
                }
            },
            result.completer());
        return result;
    }

    protected void close(Future<Integer> result, AsyncResult<Integer> ar) {
        AdminClient ac = this.ac;
        if (ac != null) {
            this.ac = null;
            vertx.executeBlocking(
                f -> {
                    try {
                        log.debug("Closing AC");
                        ac.close(10, TimeUnit.SECONDS);
                        log.debug("Closed AC");
                        f.complete();
                    } catch (Throwable t) {
                        log.warn("Ignoring error from closing admin client", t);
                        f.complete();
                    }
                },
                fut -> {
                    if (ar.failed()) {
                        if (fut.failed()) {
                            ar.cause().addSuppressed(fut.cause());
                        }
                        result.fail(ar.cause());
                    } else if (fut.failed()) {
                        result.fail(fut.cause());
                    } else {
                        result.handle(ar);
                    }
                });
        } else {
            if (ar.failed()) {
                result.complete();
            } else {
                result.handle(ar);
            }
        }
    }

    protected Future<Integer> findNextRollable(Predicate<Pod> podNeedsRestart) {
        return pollAwait().compose(monitor ->
            controller(this.ac)
                .compose(controller -> {
                    Integer podId = monitor.podId;
                    if (podId.equals(controller) && !isEmpty()) {
                        // Arrange to do the controller last when there are other brokers to be rolled
                        log.debug("Pod {} is the controller: Will roll other pods first", podId);
                        return requeueOrAbort(podNeedsRestart, monitor);
                    } else {
                        return availability().canRoll(podId).compose(canRoll -> {
                            if (canRoll) {
                                // The first pod in the list needs rolling and is rollable: We're done
                                log.debug("Can roll pod {}", podId);
                                return Future.succeededFuture(podId);
                            } else {
                                log.debug("Cannot roll pod {} right now (would affect availability): Will roll other pods first", podId);
                                return requeueOrAbort(podNeedsRestart, monitor);
                            }
                        });
                    }
                }).recover(error -> {
                    if (error instanceof AbortRollException) {
                        log.warn("Aborting roll: {}", error.toString());
                        return Future.failedFuture(error);
                    } else {
                        log.warn("Non-abortive error when determining next pod to roll " +
                                "(next pod to be rolled might not be ideal)", error);
                        return Future.succeededFuture(monitor.podId);
                    }
                }));
    }

    protected KafkaAvailability availability() {
        return new KafkaAvailability(ac);
    }

    protected String podName(Integer podId) {
        return KafkaCluster.kafkaPodName(this.cluster, podId);
    }

    /**
     * Completes the returned future <strong>on the context thread</strong> with the id of the controller of the cluster.
     * This will be -1 if there is not currently a controller.
     */
    Future<Integer> controller(AdminClient ac) {
        Future<Integer> result = Future.future();
        try {
            ac.describeCluster().controller().whenComplete((controllerNode, exception) -> {
                vertx.runOnContext(ignored -> {
                    if (exception != null) {
                        result.fail(exception);
                    } else {
                        int id = Node.noNode().equals(controllerNode) ? -1 : controllerNode.id();
                        log.debug("controller is {}", id);
                        result.complete(id);
                    }
                });
            });
        } catch (Throwable t) {
            result.fail(t);
        }
        return result;
    }

    protected void initPods(List<Integer> pods, Supplier<BackOff> backOffSupplier) {
        for (Integer po : pods) {
            this.queue.add(new Monitor(po, backOffSupplier.get()));
        }
    }

    /** Re-queue without counting this as an attempt */
    protected Future<Integer> requeue(Predicate<Pod> podNeedsRestart, Monitor monitor) {
        log.debug("Deferring restart of pod {}", monitor.podId);
        queue.add(monitor.retry(log));
        return filterAndFindNextRollable(podNeedsRestart);
    }

    /**
     * Re-queue for retry, completing the returned future after a delay, or failing
     * it if it's already been retried too many times.
     */
    protected Future<Integer> requeueOrAbort(Predicate<Pod> podNeedsRestart, Monitor monitor) {
        try {
            log.debug("Deferring restart of pod {}", monitor.podId);
            queue.add(monitor.backoffAndRetry(log));
            return filterAndFindNextRollable(podNeedsRestart);
        } catch (MaxAttemptsExceededException e) {
            return Future.failedFuture(new AbortRollException("Pod " + monitor.podId + " is still not rollable after " + monitor.backOff.maxAttempts() + " times of asking: Aborting"));
        }
    }

    /**
     * Re-queue for retry, completing the returned future after a delay or
     * if it's already been retried too many time.
     */
    protected Future<Integer> requeueOrSucceed(Predicate<Pod> podNeedsRestart, Monitor monitor) {
        try {
            log.debug("Deferring restart of {}", monitor.podId);
            queue.add(monitor.backoffAndRetry(log));
            return filterAndFindNextRollable(podNeedsRestart);
        } catch (MaxAttemptsExceededException e) {
            log.info("Pod " + monitor.podId + " is still not rollable after " + monitor.backOff.maxAttempts() + " times of asking: Restarting anyway");
            return Future.succeededFuture(monitor.podId);
        }
    }

    /**
     * If there is no next pod then return a completed Future with null result.
     * Otherwise asynchronously get the next pod, test it with the given {@code podNeedsRestart}
     * and if that pod needs a restart then complete the returned future with it.
     * If that pod didn't need a restart then remove the pod from the list of unrolled pods and recurse.
     */
    protected final Future<Integer> filterPods(Predicate<Pod> podNeedsRestart) {
        Monitor monitor = queue.peek();
        if (monitor == null) {
            return Future.succeededFuture(null);
        } else {
            log.debug("Checking whether pod {} needs to be restarted", monitor.podId);
            return podOperations.getAsync(this.namespace, podName(monitor.podId)).compose(pod -> {
                if (podNeedsRestart.test(pod)) {
                    log.debug("Pod {} needs to be restarted", monitor.podId);
                    return Future.succeededFuture(monitor.podId);
                } else {
                    // remove from pods and try next pod
                    log.debug("Pod {} does not need to be restarted", monitor.podId);
                    this.queue.remove();
                    return filterPods(podNeedsRestart);
                }
            }).recover(error -> {
                log.debug("Error filtering pods", error);
                return Future.failedFuture(error);
            });
        }
    }

    private final Future<Integer> filterAndFindNextRollable(Predicate<Pod> podNeedsRestart) {
        return filterPods(podNeedsRestart)
                .compose(pod -> {
                    if (pod != null) {
                        return findNextRollable(podNeedsRestart);
                    } else {
                        return Future.succeededFuture();
                    }
                });
    }

    protected Future<Monitor> pollAwait() {
        Monitor monitor = this.queue.poll();
        long delay = monitor.nextDeadline - System.currentTimeMillis();
        if (delay <= 0L) {
            log.debug("Proceeding with pod {}", monitor.podId);
            return Future.succeededFuture(monitor);
        } else {
            Future f = Future.future();
            log.debug("Waiting {}ms before proceeding with pod {}", delay, monitor.podId);
            vertx.setTimer(delay, timerId -> {
                log.debug("Proceeding with pod {}", monitor.podId);
                f.complete(monitor);
            });
            return f;
        }
    }

    protected boolean isEmpty() {
        return this.queue.isEmpty();
    }

    /**
     * Returns a future that completes with the next pod to roll, or null if there are no more pods to be rolled.
     */
    final Future<Integer> next(Predicate<Pod> podNeedsRestart) {
        return sort(podNeedsRestart);
    }

    @Override
    public String toString() {
        return queue.toString();
    }

    protected Future<Void> postRestartBarrier(Pod pod) {
        String namespace = pod.getMetadata().getNamespace();
        String podName = pod.getMetadata().getName();
        return podOperations.readiness(namespace, podName, pollingIntervalMs, operationTimeoutMs)
            .recover(error -> {
                log.warn("Error waiting for pod {}/{} to become ready: {}", namespace, podName, error);
                return Future.failedFuture(error);
            });
    }

}
