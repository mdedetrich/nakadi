package org.zalando.nakadi.service;

import org.zalando.nakadi.domain.Cursor;
import org.zalando.nakadi.domain.CursorError;
import org.zalando.nakadi.domain.EventType;
import org.zalando.nakadi.domain.Subscription;
import org.zalando.nakadi.exceptions.InvalidCursorException;
import org.zalando.nakadi.exceptions.NakadiException;
import org.zalando.nakadi.exceptions.NoSuchSubscriptionException;
import org.zalando.nakadi.exceptions.ServiceUnavailableException;
import org.zalando.nakadi.repository.EventTypeRepository;
import org.zalando.nakadi.repository.TopicRepository;
import org.zalando.nakadi.repository.db.SubscriptionDbRepository;
import org.zalando.nakadi.repository.zookeeper.ZooKeeperHolder;
import org.zalando.nakadi.repository.zookeeper.ZooKeeperLockFactory;
import org.zalando.nakadi.service.subscription.KafkaClient;
import org.zalando.nakadi.service.subscription.SubscriptionKafkaClientFactory;
import org.zalando.nakadi.service.subscription.model.Partition;
import org.zalando.nakadi.service.subscription.zk.ZkSubscriptionClient;
import org.zalando.nakadi.service.subscription.zk.ZkSubscriptionClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.zalando.nakadi.repository.zookeeper.ZookeeperUtils.runLocked;
import static java.text.MessageFormat.format;

@Component
public class CursorsService {

    private static final Logger LOG = LoggerFactory.getLogger(CursorsService.class);
    private static final Charset CHARSET_UTF8 = Charset.forName("UTF-8");
    private static final String PATH_ZK_OFFSET = "/nakadi/subscriptions/{0}/topics/{1}/{2}/offset";
    private static final String PATH_ZK_PARTITIONS = "/nakadi/subscriptions/{0}/topics/{1}";
    private static final String ERROR_COMMUNICATING_WITH_ZOOKEEPER = "Error communicating with zookeeper";

    private final ZooKeeperHolder zkHolder;
    private final TopicRepository topicRepository;
    private final SubscriptionDbRepository subscriptionRepository;
    private final EventTypeRepository eventTypeRepository;
    private final ZooKeeperLockFactory zkLockFactory;
    private final ZkSubscriptionClientFactory zkSubscriptionClientFactory;
    private final SubscriptionKafkaClientFactory subscriptionKafkaClientFactory;

    @Autowired
    public CursorsService(final ZooKeeperHolder zkHolder,
                                final TopicRepository topicRepository,
                                final SubscriptionDbRepository subscriptionRepository,
                                final EventTypeRepository eventTypeRepository,
                                final ZooKeeperLockFactory zkLockFactory,
                                final ZkSubscriptionClientFactory zkSubscriptionClientFactory,
                                final SubscriptionKafkaClientFactory subscriptionKafkaClientFactory) {
        this.zkHolder = zkHolder;
        this.topicRepository = topicRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.zkLockFactory = zkLockFactory;
        this.zkSubscriptionClientFactory = zkSubscriptionClientFactory;
        this.subscriptionKafkaClientFactory = subscriptionKafkaClientFactory;
    }

    public boolean commitCursors(final String subscriptionId, final List<Cursor> cursors)
            throws NakadiException, InvalidCursorException {

        final Subscription subscription = subscriptionRepository.getSubscription(subscriptionId);
        final String eventTypeName = subscription.getEventTypes().iterator().next();
        final EventType eventType = eventTypeRepository.findByName(eventTypeName);

        createSubscriptionInZkIfNeeded(subscription);
        topicRepository.validateCommitCursors(eventType.getTopic(), cursors);

        boolean allCommitted = true;
        for (final Cursor cursor : cursors) {
            final boolean cursorCommitted = commitCursor(subscriptionId, eventType.getTopic(), cursor);
            allCommitted = allCommitted && cursorCommitted;
        }
        return allCommitted;
    }

    private boolean commitCursor(final String subscriptionId, final String eventType, final Cursor cursor)
            throws ServiceUnavailableException, NoSuchSubscriptionException, InvalidCursorException {

        final String offsetPath = format(PATH_ZK_OFFSET, subscriptionId, eventType, cursor.getPartition());
        try {
            return runLocked(() -> {
                final String currentOffset = new String(zkHolder.get().getData().forPath(offsetPath), CHARSET_UTF8);
                if (topicRepository.compareOffsets(cursor.getOffset(), currentOffset) > 0) {
                    zkHolder.get().setData().forPath(offsetPath, cursor.getOffset().getBytes(CHARSET_UTF8));
                    return true;
                } else {
                    return false;
                }
            }, zkLockFactory.createLock(offsetPath));
        } catch (final IllegalArgumentException e) {
            throw new InvalidCursorException(CursorError.INVALID_FORMAT, cursor);
        } catch (final Exception e) {
            throw new ServiceUnavailableException(ERROR_COMMUNICATING_WITH_ZOOKEEPER, e);
        }
    }

    private void createSubscriptionInZkIfNeeded(final Subscription subscription) throws ServiceUnavailableException {

        final ZkSubscriptionClient subscriptionClient =
                zkSubscriptionClientFactory.createZkSubscriptionClient(subscription.getId());
        final AtomicReference<Exception> atomicReference = new AtomicReference<>();

        try {
            if (!subscriptionClient.isSubscriptionCreated()) {
                subscriptionClient.runLocked(() -> {
                    try {
                        if (!subscriptionClient.isSubscriptionCreated() && subscriptionClient.createSubscription()) {
                            final KafkaClient kafkaClient = subscriptionKafkaClientFactory
                                    .createKafkaClient(subscription);
                            final Map<Partition.PartitionKey, Long> subscriptionOffsets =
                                    kafkaClient.getSubscriptionOffsets();

                            subscriptionClient.fillEmptySubscription(subscriptionOffsets);
                        }
                    } catch (final Exception e) {
                        atomicReference.set(e);
                    }
                });
            }
        } catch (final Exception e) {
            atomicReference.set(e);
        }
        if (atomicReference.get() != null) {
            throw new ServiceUnavailableException(ERROR_COMMUNICATING_WITH_ZOOKEEPER, atomicReference.get());
        }
    }

    public List<Cursor> getSubscriptionCursors(final String subscriptionId) throws NakadiException {
        final Subscription subscription = subscriptionRepository.getSubscription(subscriptionId);
        final String eventTypeName = subscription.getEventTypes().iterator().next();
        final String topic = eventTypeRepository.findByName(eventTypeName).getTopic();
        final String partitionsPath = format(PATH_ZK_PARTITIONS, subscriptionId, topic);

        try {
            return zkHolder.get().getChildren().forPath(partitionsPath).stream()
                    .map(partition -> readCursor(subscriptionId, topic, partition))
                    .collect(Collectors.toList());
        } catch (final KeeperException.NoNodeException nne) {
            LOG.debug(nne.getMessage(), nne);
            return Collections.emptyList();
        } catch (final Exception e) {
            LOG.error(e.getMessage(), e);
            throw new ServiceUnavailableException(ERROR_COMMUNICATING_WITH_ZOOKEEPER, e);
        }
    }

    private Cursor readCursor(final String subscriptionId, final String topic, final String partition)
            throws RuntimeException {
        try {
            final String offsetPath = format(PATH_ZK_OFFSET, subscriptionId, topic, partition);
            final String currentOffset = new String(zkHolder.get().getData().forPath(offsetPath), CHARSET_UTF8);
            return new Cursor(partition, currentOffset);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

}
