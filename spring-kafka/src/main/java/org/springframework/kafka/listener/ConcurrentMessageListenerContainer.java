/*
 * Copyright 2015-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.listener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.event.ConcurrentContainerStoppedEvent;
import org.springframework.kafka.event.ConsumerStoppedEvent.Reason;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.util.Assert;

/**
 * Creates 1 or more {@link KafkaMessageListenerContainer}s based on
 * {@link #setConcurrency(int) concurrency}. If the
 * {@link ContainerProperties} is configured with {@link org.apache.kafka.common.TopicPartition}s,
 * the {@link org.apache.kafka.common.TopicPartition}s are distributed evenly across the
 * instances.
 *
 * @param <K> the key type.
 * @param <V> the value type.
 *
 * @author Marius Bogoevici
 * @author Gary Russell
 * @author Murali Reddy
 * @author Jerome Mirc
 * @author Artem Bilan
 * @author Vladimir Tsanev
 * @author Tomaz Fernandes
 * @author Wang Zhiyang
 * @author Lokesh Alamuri
 * @author Su Ko
 */
public class ConcurrentMessageListenerContainer<K, V> extends AbstractMessageListenerContainer<K, V> {

	private final List<KafkaMessageListenerContainer<K, V>> containers = new ArrayList<>();

	private final List<AsyncTaskExecutor> executors = new ArrayList<>();

	private final AtomicInteger startedContainers = new AtomicInteger();

	private int concurrency = 1;

	private boolean alwaysClientIdSuffix = true;

	private volatile @Nullable Reason reason;

	/**
	 * Construct an instance with the supplied configuration properties.
	 * The topic partitions are distributed evenly across the delegate
	 * {@link KafkaMessageListenerContainer}s.
	 * @param consumerFactory the consumer factory.
	 * @param containerProperties the container properties.
	 */
	public ConcurrentMessageListenerContainer(@Nullable ConsumerFactory<? super K, ? super V> consumerFactory,
			ContainerProperties containerProperties) {

		super(consumerFactory, containerProperties);
		Assert.notNull(consumerFactory, "A ConsumerFactory must be provided");
	}

	public int getConcurrency() {
		return this.concurrency;
	}

	/**
	 * The maximum number of concurrent {@link KafkaMessageListenerContainer}s running.
	 * Messages from within the same partition will be processed sequentially.
	 * @param concurrency the concurrency.
	 */
	public void setConcurrency(int concurrency) {
		Assert.isTrue(concurrency > 0, "concurrency must be greater than 0");
		this.concurrency = concurrency;
	}

	/**
	 * Set to false to suppress adding a suffix to the child container's client.id when
	 * the concurrency is only 1.
	 * @param alwaysClientIdSuffix false to suppress, true (default) to include.
	 * @since 2.2.14
	 */
	public void setAlwaysClientIdSuffix(boolean alwaysClientIdSuffix) {
		this.alwaysClientIdSuffix = alwaysClientIdSuffix;
	}

	/**
	 * Return the list of {@link KafkaMessageListenerContainer}s created by
	 * this container.
	 * @return the list of {@link KafkaMessageListenerContainer}s created by
	 * this container.
	 */
	public List<KafkaMessageListenerContainer<K, V>> getContainers() {
		this.lifecycleLock.lock();
		try {
			return List.copyOf(this.containers);
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public MessageListenerContainer getContainerFor(String topic, int partition) {
		this.lifecycleLock.lock();
		try {
			for (KafkaMessageListenerContainer<K, V> container : this.containers) {
				Collection<TopicPartition> assignedPartitions = container.getAssignedPartitions();
				if (assignedPartitions != null) {
					for (TopicPartition part : assignedPartitions) {
						if (part.topic().equals(topic) && part.partition() == partition) {
							return container;
						}
					}
				}
			}
			return this;
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public Collection<TopicPartition> getAssignedPartitions() {
		this.lifecycleLock.lock();
		try {
			return this.containers.stream()
					.map(KafkaMessageListenerContainer::getAssignedPartitions)
					.filter(Objects::nonNull)
					.flatMap(Collection::stream)
					.toList();
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public Map<String, Collection<TopicPartition>> getAssignmentsByClientId() {
		this.lifecycleLock.lock();
		try {
			Map<String, Collection<TopicPartition>> assignments = new HashMap<>();
			this.containers.forEach(container -> {
				Map<String, Collection<TopicPartition>> byClientId = container.getAssignmentsByClientId();
				if (byClientId != null) {
					assignments.putAll(byClientId);
				}
			});
			return assignments;
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public boolean isContainerPaused() {
		this.lifecycleLock.lock();
		try {
			boolean paused = isPauseRequested();
			if (paused) {
				for (AbstractMessageListenerContainer<K, V> container : this.containers) {
					if (!container.isContainerPaused()) {
						return false;
					}
				}
			}
			return paused;
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public boolean isChildRunning() {
		this.lifecycleLock.lock();
		try {
			for (MessageListenerContainer container : this.containers) {
				if (container.isRunning()) {
					return true;
				}
			}
			if (this.startedContainers.get() > 0) {
				return true;
			}
		}
		finally {
			this.lifecycleLock.unlock();
		}
		return false;
	}

	@Override
	public Map<String, Map<MetricName, ? extends Metric>> metrics() {
		this.lifecycleLock.lock();
		try {
			Map<String, Map<MetricName, ? extends Metric>> metrics = new HashMap<>();
			for (KafkaMessageListenerContainer<K, V> container : this.containers) {
				metrics.putAll(container.metrics());
			}
			return Collections.unmodifiableMap(metrics);
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	/*
	 * Under lifecycle lock.
	 */
	@Override
	protected void doStart() {
		if (!isRunning()) {
			checkTopics();
			ContainerProperties containerProperties = getContainerProperties();
			@Nullable TopicPartitionOffset @Nullable [] topicPartitions = containerProperties.getTopicPartitions();
			if (topicPartitions != null && this.concurrency > topicPartitions.length) {
				this.logger.warn(() -> "When specific partitions are provided, the concurrency must be less than or "
						+ "equal to the number of partitions; reduced from " + this.concurrency + " to "
						+ topicPartitions.length);
				this.concurrency = topicPartitions.length;
			}
			clearState();
			setRunning(true);

			for (int i = 0; i < this.concurrency; i++) {
				KafkaMessageListenerContainer<K, V> container =
						constructContainer(containerProperties, topicPartitions, i);
				configureChildContainer(i, container);
				if (isPauseRequested()) {
					container.pause();
				}
				container.start();
				this.containers.add(container);
			}
		}
	}

	private void configureChildContainer(int index, KafkaMessageListenerContainer<K, V> container) {
		String beanName = getBeanName();
		beanName = (beanName == null ? "consumer" : beanName) + "-" + index;
		container.setBeanName(beanName);
		ApplicationContext applicationContext = getApplicationContext();
		if (applicationContext != null) {
			container.setApplicationContext(applicationContext);
		}
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			container.setApplicationEventPublisher(publisher);
		}
		container.setClientIdSuffix(this.concurrency > 1 || this.alwaysClientIdSuffix ? "-" + index : "");
		container.setCommonErrorHandler(getCommonErrorHandler());
		container.setAfterRollbackProcessor(getAfterRollbackProcessor());
		container.setRecordInterceptor(getRecordInterceptor());
		container.setBatchInterceptor(getBatchInterceptor());
		container.setInterceptBeforeTx(isInterceptBeforeTx());
		container.setListenerInfo(getListenerInfo());
		container.setEmergencyStop(() -> stopAbnormally(() -> {
		}));
		AsyncTaskExecutor exec = container.getContainerProperties().getListenerTaskExecutor();
		if (exec == null) {
			if ((this.executors.size() > index)) {
				exec = this.executors.get(index);
			}
			else {
				exec = new SimpleAsyncTaskExecutor(beanName + "-C-");
				this.executors.add(exec);
			}
			container.getContainerProperties().setListenerTaskExecutor(exec);
		}
	}

	private KafkaMessageListenerContainer<K, V> constructContainer(ContainerProperties containerProperties,
			@Nullable TopicPartitionOffset @Nullable [] topicPartitions, int i) {

		KafkaMessageListenerContainer<K, V> container;
		if (topicPartitions == null) {
			container = new KafkaMessageListenerContainer<>(this, this.consumerFactory, containerProperties); // NOSONAR
		}
		else {
			container = new KafkaMessageListenerContainer<>(this, this.consumerFactory, // NOSONAR
					containerProperties, partitionSubset(containerProperties, i));
		}
		return container;
	}

	private @Nullable TopicPartitionOffset @Nullable [] partitionSubset(ContainerProperties containerProperties, int index) {
		@Nullable TopicPartitionOffset @Nullable [] topicPartitions = containerProperties.getTopicPartitions();
		if (topicPartitions == null) {
			return null;
		}

		if (this.concurrency == 1) {
			return topicPartitions;
		}

		int numPartitions = topicPartitions.length;

		if (numPartitions == this.concurrency) {
			return new TopicPartitionOffset[] { topicPartitions[index] };
		}

		int perContainer = numPartitions / this.concurrency;
		int start = index * perContainer;
		int end = (index == this.concurrency - 1)
				? numPartitions
				: start + perContainer;

		return Arrays.copyOfRange(topicPartitions, start, end);
	}

	/*
	 * Under lifecycle lock.
	 */
	@Override
	protected void doStop(final Runnable callback, boolean normal) {
		final AtomicInteger count = new AtomicInteger();
		if (isRunning()) {
			boolean childRunning = isChildRunning();
			setRunning(false);
			if (!childRunning) {
				callback.run();
			}
			for (KafkaMessageListenerContainer<K, V> container : this.containers) {
				if (container.isRunning()) {
					count.incrementAndGet();
				}
			}
			for (KafkaMessageListenerContainer<K, V> container : this.containers) {
				container.setFenced(true);
				if (container.isRunning()) {
					if (normal) {
						container.stop(() -> {
							if (count.decrementAndGet() <= 0) {
								callback.run();
							}
						});
					}
					else {
						container.stopAbnormally(() -> {
							if (count.decrementAndGet() <= 0) {
								callback.run();
							}
						});
					}
				}
			}
			setStoppedNormally(normal);
			// All the containers are stopped before calling stop API
			if (this.startedContainers.get() == 0) {
				publishConcurrentContainerStoppedEvent(this.reason);
			}
		}
	}

	@Override
	public void childStarted(MessageListenerContainer child) {
		this.lifecycleLock.lock();
		try {
			if (this.containers.contains(child)) {
				this.startedContainers.incrementAndGet();
			}
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public void childStopped(MessageListenerContainer child, Reason reason) {
		this.lifecycleLock.lock();
		try {
			if (!this.containers.contains(child)) {
				return;
			}
			if (this.reason == null || reason.equals(Reason.AUTH)) {
				this.reason = reason;
			}
			int startedContainersCount = this.startedContainers.decrementAndGet();
			if (startedContainersCount == 0) {
				if (!isRunning()) {
					this.containers.clear();
					publishConcurrentContainerStoppedEvent(this.reason);
				}
				boolean restartContainer = Reason.AUTH.equals(this.reason)
						&& getContainerProperties().isRestartAfterAuthExceptions();
				this.reason = null;
				if (restartContainer) {
					// This has to run on another thread to avoid a deadlock on lifecycleMonitor
					AsyncTaskExecutor exec = getContainerProperties().getListenerTaskExecutor();
					if (exec == null) {
						exec = new SimpleAsyncTaskExecutor(getListenerId() + ".authRestart");
					}
					exec.execute(this::start);
				}
			}
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@SuppressWarnings("NullAway") // Dataflow analysis limitation
	private void publishConcurrentContainerStoppedEvent(@Nullable Reason reason) {
		ApplicationEventPublisher eventPublisher = getApplicationEventPublisher();
		if (eventPublisher != null) {
			eventPublisher.publishEvent(new ConcurrentContainerStoppedEvent(this, reason));
		}
	}

	@Override
	public void enforceRebalance() {
		this.lifecycleLock.lock();
		try {
			// Since the rebalance is for the whole consumer group, there is no need to
			// initiate this operation for every single container in the group.
			final KafkaMessageListenerContainer<K, V> listenerContainer = this.containers.get(0);
			listenerContainer.enforceRebalance();
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public void pause() {
		this.lifecycleLock.lock();
		try {
			super.pause();
			this.containers.forEach(AbstractMessageListenerContainer::pause);
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public void resume() {
		this.lifecycleLock.lock();
		try {
			super.resume();
			this.containers.forEach(AbstractMessageListenerContainer::resume);
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public void pausePartition(TopicPartition topicPartition) {
		this.lifecycleLock.lock();
		try {
			this.containers
					.stream()
					.filter(container -> containsPartition(topicPartition, container))
					.forEach(container -> container.pausePartition(topicPartition));
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public void resumePartition(TopicPartition topicPartition) {
		this.lifecycleLock.lock();
		try {
			this.containers
					.stream()
					.filter(container -> container.isPartitionPauseRequested(topicPartition))
					.forEach(container -> container.resumePartition(topicPartition));
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public boolean isPartitionPaused(TopicPartition topicPartition) {
		this.lifecycleLock.lock();
		try {
			return this
					.containers
					.stream()
					.anyMatch(container -> container.isPartitionPaused(topicPartition));
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	@Override
	public boolean isInExpectedState() {
		this.lifecycleLock.lock();
		try {
			boolean isInExpectedState = isRunning() || isStoppedNormally();
			if (isInExpectedState) {
				for (KafkaMessageListenerContainer<K, V> container : this.containers) {
					if (!container.isInExpectedState()) {
						return false;
					}
				}
			}
			return isInExpectedState;
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	private boolean containsPartition(TopicPartition topicPartition, KafkaMessageListenerContainer<K, V> container) {
		Collection<TopicPartition> assignedPartitions = container.getAssignedPartitions();
		return assignedPartitions != null && assignedPartitions.contains(topicPartition);
	}

	private void clearState() {
		this.containers.clear();
		this.startedContainers.set(0);
		this.reason = null;
	}

	@Override
	public String toString() {
		return "ConcurrentMessageListenerContainer [concurrency=" + this.concurrency + ", beanName="
				+ this.getBeanName() + ", running=" + this.isRunning() + "]";
	}

}
