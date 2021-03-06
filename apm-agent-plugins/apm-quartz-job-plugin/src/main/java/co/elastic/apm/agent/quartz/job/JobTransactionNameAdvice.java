/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2020 Elastic and contributors
 * %%
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * #L%
 */
package co.elastic.apm.agent.quartz.job;

import co.elastic.apm.agent.bci.ElasticApmInstrumentation;
import co.elastic.apm.agent.bci.VisibleForAdvice;
import co.elastic.apm.agent.bci.bytebuddy.SimpleMethodSignatureOffsetMappingFactory.SimpleMethodSignature;
import co.elastic.apm.agent.collections.WeakMapSupplier;
import co.elastic.apm.agent.impl.transaction.AbstractSpan;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.util.VersionUtils;
import com.blogspot.mydailyjava.weaklockfree.WeakConcurrentMap;
import net.bytebuddy.asm.Advice;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

public class JobTransactionNameAdvice {

    private static final String FRAMEWORK_NAME = "Quartz";
    @VisibleForAdvice
    public static final WeakConcurrentMap<Class<?>, String> versionsCache = WeakMapSupplier.createMap();

    @VisibleForAdvice
    public static final Logger logger = LoggerFactory.getLogger(JobTransactionNameInstrumentation.class);

    @Advice.OnMethodEnter(suppress = Throwable.class)
    private static void setTransactionName(@Advice.Argument(value = 0) @Nullable JobExecutionContext context,
                                           @SimpleMethodSignature String signature, @Advice.Origin Class<?> clazz, @Advice.Local("transaction") Transaction transaction) {
        if (ElasticApmInstrumentation.tracer != null) {
            AbstractSpan<?> active = ElasticApmInstrumentation.tracer.getActive();
            if (context == null) {
                logger.warn("Cannot correctly name transaction for method {} because JobExecutionContext is null", signature);
                transaction = ElasticApmInstrumentation.tracer.startRootTransaction(clazz.getClassLoader());
                if (transaction != null) {
                    transaction.withName(signature)
                        .withType(JobTransactionNameInstrumentation.TRANSACTION_TYPE)
                        .activate();
                }
            } else if (active == null) {
                transaction = ElasticApmInstrumentation.tracer.startRootTransaction(clazz.getClassLoader());
                if (transaction != null) {
                    transaction.withName(context.getJobDetail().getKey().toString())
                        .withType(JobTransactionNameInstrumentation.TRANSACTION_TYPE)
                        .activate();
                }
            } else {
                logger.debug("Not creating transaction for method {} because there is already a transaction running ({})", signature, active);
            }
            if (transaction != null) {
                transaction.setFrameworkName(FRAMEWORK_NAME);
                String version = versionsCache.get(JobExecutionContext.class);
                boolean isContains = versionsCache.containsKey(JobExecutionContext.class);
                if (version == null && !isContains) {
                    version = VersionUtils.getVersionFromPomProperties(JobExecutionContext.class, "org.quartz-scheduler", "quartz");
                    versionsCache.put(JobExecutionContext.class, version);
                }
                transaction.setFrameworkVersion(version);
            }
        }
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onMethodExitException(@Advice.Argument(value = 0) @Nullable JobExecutionContext context,
                                             @Advice.Local("transaction") @Nullable Transaction transaction, @Advice.Thrown Throwable t) {
        if (transaction != null) {
            if (context != null && context.getResult() != null) {
                transaction.withResultIfUnset(context.getResult().toString());
            }
            transaction.captureException(t)
                .deactivate()
                .end();
        }
    }
}
