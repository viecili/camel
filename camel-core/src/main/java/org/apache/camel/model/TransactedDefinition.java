/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.model;

import java.lang.reflect.Method;
import java.util.Map;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import org.apache.camel.Processor;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.spi.Policy;
import org.apache.camel.spi.RouteContext;
import org.apache.camel.spi.TransactedPolicy;
import org.apache.camel.util.ObjectHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Represents an XML &lt;transacted/&gt; element
 *
 * @version $Revision$
 */
@XmlRootElement(name = "transacted")
@XmlAccessorType(XmlAccessType.FIELD)
public class TransactedDefinition extends OutputDefinition<ProcessorDefinition> {

    // TODO: Align this code with PolicyDefinition

    // JAXB does not support changing the ref attribute from required to optional
    // if we extend PolicyDefinition so we must make a copy of the class
    @XmlTransient
    public static final String PROPAGATION_REQUIRED = "PROPAGATION_REQUIRED";

    private static final transient Log LOG = LogFactory.getLog(TransactedDefinition.class);

    @XmlTransient
    protected Class<? extends Policy> type = TransactedPolicy.class;
    @XmlAttribute
    protected String ref;
    @XmlTransient
    private Policy policy;

    public TransactedDefinition() {
    }

    public TransactedDefinition(Policy policy) {
        this.policy = policy;
    }

    @Override
    public String toString() {
        return "Transacted[" + description() + "]";
    }

    @Override
    public String getShortName() {
        return "transacted";
    }

    @Override
    public String getLabel() {
        if (ref != null) {
            return "ref:" + ref;
        } else if (policy != null) {
            return policy.toString();
        } else {
            return "";
        }
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    /**
     * Sets a policy type that this defition should scope within.
     * <p/>
     * Is used for convention over configuration situations where the policy
     * should be automatic looked up in the registry and it should be based
     * on this type. For instance a {@link org.apache.camel.spi.TransactedPolicy}
     * can be set as type for easy transaction configuration.
     * <p/>
     * Will by default scope to the wide {@link Policy}
     *
     * @param type the policy type
     */
    public void setType(Class<? extends Policy> type) {
        this.type = type;
    }

    /**
     * Sets a reference to use for lookup the policy in the registry.
     *
     * @param ref the reference
     * @return the builder
     */
    public TransactedDefinition ref(String ref) {
        setRef(ref);
        return this;
    }

    @Override
    public Processor createProcessor(RouteContext routeContext) throws Exception {
        Processor childProcessor = createOutputsProcessor(routeContext);

        Policy policy = resolvePolicy(routeContext);
        ObjectHelper.notNull(policy, "policy", this);
        return policy.wrap(routeContext, childProcessor);
    }


    protected String description() {
        if (policy != null) {
            return policy.toString();
        } else {
            return "ref:" + ref;
        }
    }

    protected Policy resolvePolicy(RouteContext routeContext) {
        if (policy != null) {
            return policy;
        }
        return doResolvePolicy(routeContext, getRef(), type);
    }

    @SuppressWarnings("unchecked")
    protected static Policy doResolvePolicy(RouteContext routeContext, String ref, Class<? extends Policy> type) {
        // explicit ref given so lookup by it
        if (ObjectHelper.isNotEmpty(ref)) {
            return routeContext.lookup(ref, Policy.class);
        }

        // no explicit reference given from user so we can use some convention over configuration here

        // try to lookup by scoped type
        Policy answer = null;
        if (type != null) {
            // try find by type, note that this method is not supported by all registry
            Map types = routeContext.lookupByType(type);
            if (types.size() == 1) {
                // only one policy defined so use it
                Object found = types.values().iterator().next();
                if (type.isInstance(found)) {
                    return type.cast(found);
                }
            }
        }

        // for transacted routing try the default REQUIRED name
        if (type == TransactedPolicy.class) {
            // still not found try with the default name PROPAGATION_REQUIRED
            answer = routeContext.lookup(PROPAGATION_REQUIRED, TransactedPolicy.class);
        }

        // still no policy found then try lookup the platform transaction manager and use it as policy
        if (answer == null && type == TransactedPolicy.class) {
            Class tmClazz = routeContext.getCamelContext().getClassResolver().resolveClass("org.springframework.transaction.PlatformTransactionManager");
            if (tmClazz != null) {
                // see if we can find the platform transaction manager in the registry
                Map<String, Object> maps = routeContext.lookupByType(tmClazz);
                if (maps.size() == 1) {
                    // only one platform manager then use it as default and create a transacted
                    // policy with it and default to required

                    // as we do not want dependency on spring jars in the camel-core we use
                    // reflection to lookup classes and create new objects and call methods
                    // as this is only done during route building it does not matter that we
                    // use reflection as performance is no a concern during route building
                    Object transactionManager = maps.values().iterator().next();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("One instance of PlatformTransactionManager found in registry: " + transactionManager);
                    }
                    Class txClazz = routeContext.getCamelContext().getClassResolver().resolveClass("org.apache.camel.spring.spi.SpringTransactionPolicy");
                    if (txClazz != null) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Creating a new temporary SpringTransactionPolicy using the PlatformTransactionManager: " + transactionManager);
                        }
                        TransactedPolicy txPolicy = ObjectHelper.newInstance(txClazz, TransactedPolicy.class);
                        Method method;
                        try {
                            method = txClazz.getMethod("setTransactionManager", tmClazz);
                        } catch (NoSuchMethodException e) {
                            throw new RuntimeCamelException("Cannot get method setTransactionManager(PlatformTransactionManager) on class: " + txClazz);
                        }
                        ObjectHelper.invokeMethod(method, txPolicy, transactionManager);
                        return txPolicy;
                    } else {
                        LOG.warn("Cannot create a transacted policy as camel-spring.jar is not on the classpath!");
                    }
                } else {
                    if (LOG.isDebugEnabled()) {
                        if (maps.isEmpty()) {
                            LOG.debug("No PlatformTransactionManager found in registry.");
                        } else {
                            LOG.debug("Found " + maps.size() + " PlatformTransactionManager in registry. "
                                    + "Cannot determine which one to use. Please configure a TransactionTemplate on the policy");
                        }
                    }
                }
            }
        }

        return answer;
    }

}
