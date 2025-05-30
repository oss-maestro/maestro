/*
 * Copyright 2024 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.maestro.server.properties;

import com.netflix.maestro.engine.properties.SelProperties;
import com.netflix.maestro.engine.properties.StepActionProperties;
import com.netflix.maestro.models.Constants;
import com.netflix.maestro.queue.properties.QueueProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Maestro engine related config properties. */
@AllArgsConstructor
@Getter
@ConfigurationProperties(prefix = Constants.MAESTRO_QUALIFIER)
public class MaestroProperties {
  private final QueueProperties queue;
  private final SelProperties sel;
  private final StepActionProperties stepAction;
}
