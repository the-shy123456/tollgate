/*
 * Copyright 2026 Tollgate Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.tollgate.core.meter;

/**
 * Receives a record for every completed call.
 *
 * <p>Listeners must be fast and must not throw. They run on the request thread, after the
 * response is ready but before it is returned to the caller, so a listener that blocks on a
 * network write would add its latency to every single call. Buffering, batching or handing the
 * record to another thread is the listener's responsibility.
 *
 * <p>A listener that throws is isolated: the exception is swallowed and the call still returns,
 * because losing a metric is preferable to failing a request that already succeeded.
 */
@FunctionalInterface
public interface UsageListener {

    /**
     * Called once per completed call.
     *
     * @param record the usage record
     */
    void onUsage(UsageRecord record);
}
