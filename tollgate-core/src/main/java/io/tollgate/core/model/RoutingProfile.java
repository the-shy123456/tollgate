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
package io.tollgate.core.model;

/**
 * The caller's preference when Tollgate has to choose among several candidate models.
 *
 * <p>Routing profiles are hints, not commands: a profile is always resolved against the set of
 * models that are actually eligible for the request (context window, capability, quota headroom).
 * If the preferred end of the spectrum is unavailable the router falls back to the next best
 * candidate rather than failing the call.
 */
public enum RoutingProfile {

    /** Minimise spend. Picks the cheapest model that can still serve the request. */
    CHEAPEST,

    /** Balance quality against cost. This is the default. */
    BALANCED,

    /** Prefer the highest quality tier regardless of price. */
    BEST_QUALITY,

    /** Prefer the model with the lowest expected latency. */
    LATENCY_FIRST
}
