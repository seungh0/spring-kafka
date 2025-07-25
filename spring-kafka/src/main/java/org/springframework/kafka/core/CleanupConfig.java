/*
 * Copyright 2018-present the original author or authors.
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

package org.springframework.kafka.core;

/**
 * Specifies time of {@link org.apache.kafka.streams.KafkaStreams#cleanUp()} execution.
 *
 * @author Pawel Szymczyk
 */
public class CleanupConfig {

	private final boolean onStart;

	private final boolean onStop;

	public CleanupConfig() {
		this(false, false);
	}

	public CleanupConfig(boolean onStart, boolean onStop) {
		this.onStart = onStart;
		this.onStop = onStop;
	}

	public boolean cleanupOnStart() {
		return this.onStart;
	}

	public boolean cleanupOnStop() {
		return this.onStop;
	}

}
